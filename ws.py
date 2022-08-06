from websocket import create_connection

ws = create_connection("ws://localhost:50444/")
print(ws.recv())
ws.close()
