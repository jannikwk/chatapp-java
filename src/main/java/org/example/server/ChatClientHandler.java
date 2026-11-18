package org.example.server;

import org.example.protocol.Message;
import org.example.protocol.MessageParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ChatClientHandler implements Runnable {
    private static final int MAX_LINE_LENGTH = 4096;
    private static final int MAX_USERNAME_LENGTH = 64;
    private static final int MAX_MESSAGE_LENGTH = 2048;
    private static final int SOCKET_IDLE_TIMEOUT_MS = 300_000;

    private final Socket clientSocket;
    private final ChatRoomManager chatRoomManager;
    private final PrintWriter out;
    private final MessageParser messageParser;
    private final Set<String> joinedRooms = ConcurrentHashMap.newKeySet();
    private String username;

    public ChatClientHandler(Socket clientSocket, ChatRoomManager chatRoomManager) throws IOException {
        this.clientSocket = clientSocket;
        this.chatRoomManager = chatRoomManager;
        this.clientSocket.setSoTimeout(SOCKET_IDLE_TIMEOUT_MS);
        this.out = new PrintWriter(new OutputStreamWriter(clientSocket.getOutputStream()), true);
        this.messageParser = new MessageParser();
    }

    @Override
    public void run() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))) {
            String rawClientMessage;
            while ((rawClientMessage = in.readLine()) != null) {
                if (rawClientMessage.isBlank()) {
                    continue;
                }
                if (rawClientMessage.length() > MAX_LINE_LENGTH) {
                    sendError("Beskeden er for lang", "");
                    logAction("CLIENT", "Indsendte en for lang besked");
                    continue;
                }

                Message clientMessage;
                try {
                    clientMessage = messageParser.parseClientMessage(rawClientMessage);
                } catch (IllegalArgumentException e) {
                    sendError("Fejl i beskedformat: " + e.getMessage(), "");
                    logAction("CLIENT", "Indsendte en ugyldig besked");
                    continue;
                }

                try {
                    handleClientMessage(clientMessage);
                } catch (Exception e) {
                    sendError("Fejl ved behandling af besked: " + (e.getMessage() == null ? "" : e.getMessage()), "");
                    logAction("CLIENT", "Forårsagede en behandlingsfejl");
                }
            }
        } catch (IOException e) {
            logAction("CLIENT", "Forbindelsen blev afbrudt eller fejlede");
        } finally {
            disconnect();
            ClientRegistry.unregister(this);
            try {
                clientSocket.close();
            } catch (IOException e) {
                logAction("CLIENT", "Socketforbindelsen kunne ikke lukkes");
            }
        }
    }

    private void handleClientMessage(Message clientMessage) {
        if (clientMessage == null || clientMessage.getType() == null) {
            sendError("Ukendt kommando", "");
            return;
        }

        switch (clientMessage.getType().toUpperCase()) {
            case "LOGIN" -> login(clientMessage.getPayload());
            case "JOIN_ROOM" -> joinRoom(clientMessage.getTarget());
            case "LEAVE_ROOM" -> leaveRoom(clientMessage.getTarget());
            case "TEXT" -> sendTextToRoom(clientMessage.getTarget(), clientMessage.getPayload());
            case "PRIVATE" -> sendPrivateMessage(clientMessage.getTarget(), clientMessage.getPayload());
            case "HISTORY" -> showHistory(clientMessage.getTarget());
            default -> {
                sendError("Ukendt kommando: " + clientMessage.getType(), clientMessage.getTarget());
                logAction(username == null ? "CLIENT" : username, "Submitted an unknown command");
            }
        }
    }

    public void login(String requestedUsername) {
        String normalizedUsername = normalizeUsername(requestedUsername);
        if (!isValidUsername(normalizedUsername, "LOGIN")) {
            return;
        }

        if (!ClientRegistry.registerUsername(this, normalizedUsername)) {
            sendError("Brugernavnet er allerede i brug", "");
            logAction("LOGIN", "Mislykket - brugernavn allerede i brug");
            return;
        }

        this.username = normalizedUsername;
        sendMessageToClient(new Message(Instant.now(), "OK", "Server", this.username, "Login godkendt"));
        logAction(normalizedUsername, "Login successful");
    }

    private void joinRoom(String roomName) {
        if (requireLogin()) {
            return;
        }

        String normalizedRoomName = normalizeRoomName(roomName);
        if (requireRoomName(normalizedRoomName)) {
            return;
        }

        if (chatRoomManager.joinRoom(normalizedRoomName, username)) {
            joinedRooms.add(normalizedRoomName);
            sendMessageToClient(new Message(Instant.now(), "OK", "Server", normalizedRoomName,
                    "Du deltager nu i rummet " + normalizedRoomName));
            logAction(username, "Joined room " + normalizedRoomName);
            showHistory(normalizedRoomName);
            return;
        }

        if (isInRoom(normalizedRoomName)) {
            sendError("Du deltager allerede i rummet", normalizedRoomName);
            logAction(username, "Attempted to join an already joined room");
            return;
        }

        sendError("Kunne ikke tilslutte til rummet", normalizedRoomName);
        logAction(username, "Failed to join room");
    }

    private void leaveRoom(String roomName) {
        if (requireLogin()) {
            return;
        }

        String normalizedRoomName = normalizeRoomName(roomName);
        if (requireRoomName(normalizedRoomName)) {
            return;
        }

        if (chatRoomManager.leaveRoom(normalizedRoomName, username)) {
            joinedRooms.remove(normalizedRoomName);
            sendMessageToClient(new Message(Instant.now(), "OK", "Server", normalizedRoomName,
                    "Du har forladt rummet " + normalizedRoomName));
            logAction(username, "Left room " + normalizedRoomName);
            return;
        }

        sendError("Du er ikke medlem af rummet", normalizedRoomName);
        logAction(username, "Attempted to leave a room they are not in");
    }

    private void sendTextToRoom(String roomName, String payload) {
        if (requireLogin()) {
            return;
        }

        String normalizedRoomName = normalizeRoomName(roomName);
        if (requireRoomName(normalizedRoomName)) {
            return;
        }

        if (payload == null || payload.length() > MAX_MESSAGE_LENGTH) {
            sendError("Beskedens indhold er ugyldigt eller for langt", normalizedRoomName);
            return;
        }
        if (!requireRoomMembership(normalizedRoomName, "attempted to send a message")) {
            return;
        }

        Message roomMessage = new Message(Instant.now(), "TEXT", username, normalizedRoomName, payload);
        chatRoomManager.addRoomMessage(normalizedRoomName, roomMessage);
        broadcastToRoom(normalizedRoomName, roomMessage);
        logAction(username, "Sent a message to room " + normalizedRoomName);
    }

    private void showHistory(String roomName) {
        if (requireLogin()) {
            return;
        }

        String resolvedRoomName = resolveHistoryRoomName(roomName);
        if (resolvedRoomName == null) {
            return;
        }

        if (!requireRoomMembership(resolvedRoomName, "attempted to request history")) {
            return;
        }

        List<Message> history = chatRoomManager.getRoomHistory(resolvedRoomName);
        logAction(username, "Requested history for room " + resolvedRoomName);
        sendMessageToClient(new Message(Instant.now(), "OK", "Server", resolvedRoomName,
                "Historik for rummet " + resolvedRoomName + ":"));

        if (history.isEmpty()) {
            sendMessageToClient(new Message(Instant.now(), "OK", "Server", resolvedRoomName,
                    "Der er ingen historik i rummet endnu"));
            return;
        }

        for (Message historicalMessage : history) {
            sendMessageToClient(new Message(Instant.now(), "TEXT", historicalMessage.getSender(),
                    historicalMessage.getTarget(), historicalMessage.getPayload()));
        }
    }

    private String resolveHistoryRoomName(String roomName) {
        if (roomName != null && !roomName.isBlank()) {
            return normalizeRoomName(roomName);
        }

        if (joinedRooms.isEmpty()) {
            sendError("Du er ikke medlem af noget rum", "");
            return null;
        }

        if (joinedRooms.size() == 1) {
            return joinedRooms.iterator().next();
        }

        sendError("Angiv et rum-navn, du deltager i flere rum", "");
        return null;
    }

    public void sendPrivateMessage(String recipientUsername, String payload) {
        if (requireLogin()) {
            return;
        }

        if (recipientUsername == null || recipientUsername.isBlank()) {
            sendError("Modtager mangler", "");
            return;
        }

        String normalizedRecipient = normalizeUsername(recipientUsername);
        if (normalizedRecipient.isEmpty()
                || normalizedRecipient.length() > MAX_USERNAME_LENGTH
                || payload == null
                || payload.length() > MAX_MESSAGE_LENGTH) {
            sendError("Modtager eller besked er ugyldig", normalizedRecipient);
            return;
        }
        ChatClientHandler recipient = ClientRegistry.getClient(normalizedRecipient);
        if (recipient == null) {
            sendError("Brugeren findes ikke", normalizedRecipient);
            logAction(username, "Attempted to send a private message to an unknown user");
            return;
        }

        Message privateMessage = new Message(Instant.now(), "PRIVATE", username, normalizedRecipient, payload);
        recipient.sendMessageToClient(privateMessage);
        sendMessageToClient(new Message(Instant.now(), "OK", "Server", normalizedRecipient, "Besked sendt"));
        logAction(username, "Sent a private message");
    }

    private void broadcastToRoom(String roomName, Message roomMessage) {
        for (ChatClientHandler client : ClientRegistry.getAllClients()) {
            if (client != null && client.isInRoom(roomName)) {
                client.sendMessageToClient(roomMessage);
            }
        }
    }

    private boolean requireLogin() {
        if (username == null) {
            sendError("Du skal logge ind først", "");
            return true;
        }
        return false;
    }

    private boolean requireRoomName(String roomName) {
        if (roomName == null || roomName.isBlank()) {
            sendError("Rum-navn mangler", username);
            return true;
        }
        return false;
    }

    private boolean requireRoomMembership(String roomName, String failedAction) {
        if (!chatRoomManager.isUserInRoom(roomName, username)) {
            sendError("Du er ikke medlem af rummet", roomName);
            logAction(username, failedAction + " without room membership");
            return false;
        }
        return true;
    }

    private boolean isValidUsername(String normalizedUsername, String actor) {
        if (normalizedUsername.isEmpty()) {
            sendError("Brugernavn mangler", "");
            logAction(actor, "Mislykket - brugernavn mangler");
            return false;
        }
        if (normalizedUsername.length() > MAX_USERNAME_LENGTH) {
            sendError("Brugernavnet er for langt", "");
            logAction(actor, "Mislykket - brugernavn for langt");
            return false;
        }
        return true;
    }

    private String normalizeUsername(String username) {
        return username == null ? "" : username.trim();
    }

    private String normalizeRoomName(String roomName) {
        return roomName == null ? "" : roomName.trim().toLowerCase();
    }

    private void sendError(String errorMessage, String target) {
        sendMessageToClient(new Message(Instant.now(), "ERROR", "Server", target, errorMessage));
    }

    private boolean isInRoom(String roomName) {
        return roomName != null && joinedRooms.contains(normalizeRoomName(roomName));
    }

    private void disconnect() {
        if (username != null) {
            chatRoomManager.removeUserFromAllRooms(username);
            ClientRegistry.unregister(this);
            logAction(username, "Disconnected");
        }
    }

    private void logAction(String actor, String action) {
        Message actionMessage = new Message(Instant.now(), "Action", "Server", actor, action);
        System.out.println("[Server] " + messageParser.formatServerMessage(actionMessage));
    }

    private void sendMessageToClient(Message message) {
        String formattedMessage = messageParser.formatServerMessage(message);
        out.println(formattedMessage);
    }

    public String getUsername() {
        return username;
    }
}