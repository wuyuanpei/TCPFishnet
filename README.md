# uTCP on Fishnet
This project implements a one-directional TCP protocol with flow control and congestion control on Fishnet simulator/emulator

- Author: Richard Wu
- Date: 2021 Oct 24

## Part 1: the basic protocol with socket API

### Packet and Transport
- ``class Packet`` is used to simulate packets in network layer, thus it contains IP header like ``dest`` address, ``src`` address, ``ttl``, etc.
- The payload of ``Packet`` is a packeted object of ``class Transport`` which simulates transport layer, containing ``srcPort``, ``destPort``, ``seqNum``, etc.

### TCPManager
- ``class TCPManager`` is used to manage all the TCP sockets. It contains an array list (named ``sockets``) of sockets (``class TCPSock``).
- When a new packet is received, ``node.receivePacket()`` will forward the packet to ``tcpMan.onReceive()`` which will use ``findBestMatch()`` to multiplex the packet to one of the sockets in ``sockets`` and call ``bestSock.onReceive()``.
- ``findBestMatch()`` will first find a socket with exactly the same four-tuple, i.e.,``dest`` (``localAddr``), ``src`` (``remoteAddr``), ``destPort`` (``localPort``), ``srcPort`` (``remotePort``) as the incoming packet. If nothing is found, it will find a socket with the same local port and address but ``-1`` remote port and address, i.e., the socket is a welcome socket.
- If ``findBestMatch()`` returns ``null``, then ``tcpMan.onReceive`` will send a ``FIN`` packet and print out ``X``.
- ``isUsed()`` will go through ``sockets`` and check whether the same four-tuple has already existed. It will be used by functions like ``bind()``.

### TCPSock
#### Basic Fields
- A ``TCPSock`` object has state ``CLOSED``, ``LISTEN``, ``SYN_SENT``, ``ESTABLISHED``, ``SHUTDOWN``.
- A ``TCPSock`` object has fields ``localPort``, ``localAddr``, ``remotePort``, ``remoteAddr``
- A ``TCPSock`` object has a field ``connQ`` which is an array list of ``TCPSock``. It is a connection queue only used by the welcome socket. The connection queue can have maximum size ``backlog``. ``connQ`` is a local data structure in welcome socket, which should not be confused with the global data structure ``sockets`` in ``TPCManager``.
- A ``TCPSock`` object has a field ``window`` which is a byte array. It is used at the receiver side to store data and wait for ``read()``. Currently, in part 1, ``window`` has size ``Transport.MAX_PAYLOAD_SIZE``, i.e., it can only hold one TCP packet with full payload size. It has two pointers ``readPointer`` and ``writePointer`` for accessing the window.
- A ``TCPSock`` object has fields ``startSeq`` (to track the seq number sent) and ``nextSeq`` (to track the seq number to be acked). In part 1 "stop and wait" structure, if ``startSeq == nextSeq``, then it is okay to send new packet.

#### Basic functions
- When creating a new ``TCPSock`` object:
```java
this.state = State.CLOSED;
this.localAddr = localAddr;
this.localPort = -1; // should be set by bind()
this.remoteAddr = -1;
this.remotePort = -1;
```
- ``bind()``: this function will check ``tcpMan.isUsed == false`` and set ``this.localPort`` and then put the socket into ``tcpMan.sockets``
- ``listen()``: this function will transfer the state from ``CLOSED`` to ``LISTEN`` and set up the ``connQ``.
- ``accept()``: this function will return the first socket in ``connQ`` and add it into ``tcpMan.sockets``. If ``state != State.LISTEN || connQ == null || connQ.size() == 0``, then ``null`` is returned. **It is important that before ``accept()`` is called, the newly created socket, though ``ESTABLISHED``, is not in ``sockets``, so the socket cannot be directly reached by ``TCPManager`` in ``findBestMatch()``**.
- ``connect()``: this function will set ``this.remotePort`` and ``this.remoteAddr``, and then send a ``SYN`` packet with ``startSeq`` equal to a random number between 1 to 1000, and print out ``S``. It will transfer the state from ``CLOSED`` to ``SYN_SENT``. A timeout (1 sec) will be set to resend the ``SYN`` packet if ``ACK`` is not received timely.
- ``release()``: this function simply set the state to ``CLOSED`` and remove the socket from ``tcpMan.sockets``.
- ``close()``: this function will shut down the socket gracefully. If the socket is a welcome socket, then call ``release()``. If ``nextSeq == startSeq``, meaning that no packet need to be resent (always true for server side), then send ``FIN`` print ``F`` and ``release()``. Otherwise, do nothing and set the state to ``SHUTDOWN``.
- ``write()``: this function will send a packet if there is no in-flight packet (i.e., ``startSeq == nextSeq``). The packet will have payload size ``sendLen = Math.min(len, buf.length - pos, Transport.MAX_PAYLOAD_SIZE)``. The packet will have seq number equal to ``startSeq``. After sending the packet, ``nextSeq = startSeq + sendLen``. A timeout will be set to resend the packet if ``ACK`` is not received. Timeout will follow the formula:
```
Timeout = EstRTT + 4 * DevRTT
EstRTT = (1 - alpha) * EstRTT + alpha * SampleRTT
DevRTT = (1 - beta) * DevRTT + beta * |SampleRTT - EstRTT|
alpha = 0.125
beta = 0.25
```
- Note that for Part 1 "stop and wait", we do not buffer the content given by the argument of ``write()``. If the ``write`` cannot happen (due to any in-flight packet), ``write`` simply returns 0. Later in part 2, we implement more sophisticated buffer that buffers write.
- ``read()``: this function will simply read from ``window`` and update ``readPointer``.
- ``onReceive()``: this function is the most complicated one that handles incoming packet. 

#### Receive different packets
- For receiving ``SYN``: the welcome socket will create a new socket and add it to ``connQ`` (if the same four-tuple does not exist). It will send back an ``ACK`` packet (ack with ``seq + 1``). ``FIN`` will be sent if the state is not correct or ``connQ.size >= backlog``. Note that an ``ESTABLISED`` packet can receive ``SYN`` if the first ``SYN`` times out and the second gets redirected to the connection socket rather than the welcome socket, because it has been accepted (i.e., put into ``sockets``).
- For receiving ``ACK``: if the current state is ``SYN_SENT`` and ``seq == startSeq + 1``, i.e. this is the ``ACK`` for my ``SYN``, then the state will be set to ``ESTABLISHED``, and ``startSeq += 1``. If the current state is ``ESTABLISHED`` and ``seq == nextSeq`` (i.e., this is the expected ``ACK``), then ``startSeq`` will be set to ``nextSeq``. If the current state is ``SHUTDOWN``, then ``close()`` will be called after receiving an expected ``ACK``.
- For receiving ``DATA``: if a welcome socket receives a ``DATA`` packet, it will forward it to the socket in ``connQ`` (the socket has not been ``accepted`` yet) if any, otherwise, ``FIN`` will be sent back. If the connection socket receives a ``DATA`` packet, it will first check whether ``seq == startSeq`` (i.e., the packet is expected) and it has enough window size. If so, ``startSeq += payload.length`` and send ``ACK`` with ``seq == startSeq``. Finally, it will save the payload in ``window``. For out of order packet or the case where ``window`` does not have enough space, ``ACK`` with the old ``seq`` will be sent back.
- For receiving ``FIN`` packet: simply call ``release()`` and print out ``F``. If this is a connection socket and ``onReceive()`` has not been called for a long time, then ``release()`` will be called using the callback timer. Therefore, even if ``FIN`` is lost, a connection socket will eventually be closed. (Note that the server need to explicitly close its welcome socket).