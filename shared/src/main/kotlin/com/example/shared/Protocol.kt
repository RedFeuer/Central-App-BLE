package com.example.shared

/* ограничения по заданию */
object Protocol {
    const val STREAM_BLOCK_SIZE = 160
    const val STREAM_PERIOD_MS = 60L
}

sealed interface Command {
    data object Ping : Command
    data object Pong : Command
    data object StartStream : Command
    data object StopStream : Command
}

object CommandCodec {
    private const val PING: Byte = 0x01
    private const val PONG: Byte = 0x02
    private const val START: Byte = 0x10
    private const val STOP: Byte = 0x11

    fun encode(cmd: Command): ByteArray = byteArrayOf(
        when (cmd) {
            Command.Ping -> PING
            Command.Pong -> PONG
            Command.StartStream -> START
            Command.StopStream -> STOP
        }
    )

    fun decode(bytes: ByteArray): Command? = when (bytes.firstOrNull()) {
        PING -> Command.Ping
        PONG -> Command.Pong
        START -> Command.StartStream
        STOP -> Command.StopStream
        else -> null
    }
}
