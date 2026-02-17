package com.example.shared

/* ограничения по заданию */
object Protocol {
    const val STREAM_BLOCK_SIZE = 160
    const val STREAM_PERIOD_MS = 60L
}

sealed interface Command {
    data object Ping : Command
    data object Pong : Command
    data object StartTransfer : Command
    data object StopTransfer : Command
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
            Command.StartTransfer -> START
            Command.StopTransfer -> STOP
        }
    )

    fun decode(bytes: ByteArray): Command? = when (bytes.firstOrNull()) {
        PING -> Command.Ping
        PONG -> Command.Pong
        START -> Command.StartTransfer
        STOP -> Command.StopTransfer
        else -> null
    }
}
