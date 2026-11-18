# Chat Program (Java)
> A simple console-based chat program where multiple clients communicate with each other through a server.

## Tests
| Test                                 | Expected result                                   |
|--------------------------------------|---------------------------------------------------|
| Three clients connect simultaneously | All three clients can connect.                    |
| Two users select the same username   | The second user receives an error message.        |
| A user sends a message in a room     | Only users in that room receive the message.      |
| A user sends a private message       | Only the specified recipient can see the message. |
| A client sends a malformed message   | The server sends an error and continues running.  |
| A client disconnects unexpectedly    | The server removes the client from the registry.  |
| A user joins a room                  | The user can see the room history.                |

## Startup guide & Protocol description
### Instructions for starting the program 

1. Run the server's main method.
2. If the port is in use, change the constant named `PORT` in both the server and the client.
3. Run the client's main method.

### Protocol description
- Our protocol is implemented by `MessageParser`.
- It converts raw text into fields that the program uses to determine what action should occur.
- The server sends messages using `TIMESTAMP|TYPE|SENDER|TARGET|PAYLOAD`, for example `TIMESTAMP|PRIVATE|FRANK|BOB|hello bob`.
- The protocol has two main formats: clients use `TYPE|TARGET|PAYLOAD`, while servers use `TIMESTAMP|TYPE|SENDER|TARGET|PAYLOAD`.
- Server action logs use the same server format but contain a generic action as the payload. They do not expose the actual chat message.

#### Server-logging
![Example of server logging](src/main/resources/images/img.png)

This is an example of how the server logs a user's actions using the server protocol.

## Thread model and shared resources
The application uses multithreading, allowing it to handle several tasks simultaneously instead of waiting for one task to finish before starting another.
An `ExecutorService` limits the number of client handler threads.

## Selected extension
`Chat room message history`

We selected message history because it improves the chat experience by letting users see previous activity after joining a room.
Users can view the history when joining a room or by using the history command.
The server verifies that the user is a member of the room and handles an empty room history correctly.
Private messages are not included because the extension is limited to room history.

### Class diagram
![Class diagram](src/main/resources/uml/class-diagram.drawio.png)

### Sequence diagram
![Sequence diagram](src/main/resources/uml/sequence-diagram.drawio.jpg)