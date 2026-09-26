package br.ufmg.sistole.source

import java.util.UUID

/** Destino das amostras decodificadas, independente do transporte. */
interface SampleSink {
    /** [seq] é o índice (0–65535) da primeira amostra, ou -1 se o transporte não numera. */
    fun onSamples(seq: Int, adc: IntArray, count: Int, loFlags: Int)
    fun onMeta(meta: Map<String, String>)
    fun onBattery(mv: Int)
    fun onConsole(line: String)
    fun onPacket(bytes: Int)
}

object SistoleProtocol {
    val SERVICE: UUID = UUID.fromString("8a3e0001-3c1d-4f2b-9e6a-5f1b2c7d9e10")
    val STREAM: UUID = UUID.fromString("8a3e0002-3c1d-4f2b-9e6a-5f1b2c7d9e10")
    val INFO: UUID = UUID.fromString("8a3e0003-3c1d-4f2b-9e6a-5f1b2c7d9e10")
    val CONTROL: UUID = UUID.fromString("8a3e0004-3c1d-4f2b-9e6a-5f1b2c7d9e10")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val FLAG_LO_PLUS = 1
    const val FLAG_LO_MINUS = 2
    const val FLAG_BATTERY = 4

    /** `fs=500;bits=12;vref=3.3;fw=…` → mapa. */
    fun parseMeta(text: String): Map<String, String> =
        text.trim().removePrefix("#").split(';', ',')
            .mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
            }.toMap()

    fun crc8(data: ByteArray, from: Int, len: Int): Int {
        var crc = 0
        for (i in from until from + len) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF }
        }
        return crc
    }
}

/** Decodifica o pacote binário (BLE notify ou carga do quadro serial binário). */
class PacketDecoder(private val sink: SampleSink) {
    private var buf = IntArray(64)

    /** Retorna false se o pacote estiver malformado. */
    fun decode(p: ByteArray, off: Int = 0, len: Int = p.size): Boolean {
        if (len < 4) return false
        fun u8(i: Int) = p[off + i].toInt() and 0xFF
        fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        val seq = u16(0)
        val flags = u8(2)
        val n = u8(3)
        val need = 4 + 2 * n + if (flags and SistoleProtocol.FLAG_BATTERY != 0) 2 else 0
        if (len < need) return false
        if (buf.size < n) buf = IntArray(n)
        for (i in 0 until n) buf[i] = u16(4 + 2 * i)
        sink.onPacket(len)
        sink.onSamples(seq, buf, n, flags and 3)
        if (flags and SistoleProtocol.FLAG_BATTERY != 0) sink.onBattery(u16(4 + 2 * n))
        return true
    }
}

/**
 * Parser do modo texto da serial: `seq,adc,lo`, `adc` ou metadados `#chave=valor;…`.
 * Aceita CR/LF, ignora linhas vazias ou inválidas (o início da conexão costuma vir cortado).
 */
class CsvLineParser(private val sink: SampleSink) {
    private val line = StringBuilder(64)
    private val one = IntArray(1)
    private var consoleEvery = 0

    fun feed(data: ByteArray, len: Int = data.size) {
        sink.onPacket(len)
        for (i in 0 until len) {
            val c = (data[i].toInt() and 0xFF).toChar()
            if (c == '\n' || c == '\r') {
                if (line.isNotEmpty()) handle(line.toString())
                line.setLength(0)
            } else if (line.length < 200) {
                line.append(c)
            } else {
                line.setLength(0)
            }
        }
    }

    private fun handle(raw: String) {
        val s = raw.trim()
        if (s.isEmpty()) return
        if (s[0] == '#') {
            sink.onConsole(s)
            val meta = SistoleProtocol.parseMeta(s)
            meta["bat"]?.toIntOrNull()?.let { sink.onBattery(it) }
            if (meta.isNotEmpty()) sink.onMeta(meta)
            return
        }
        // amostra a amostra seria muito texto; o console recebe 1 a cada 25 linhas
        if (++consoleEvery >= 25) { consoleEvery = 0; sink.onConsole(s) }
        val parts = s.split(',', ';', '\t', ' ').filter { it.isNotEmpty() }
        when (parts.size) {
            1 -> {
                val adc = parts[0].toIntOrNull() ?: return
                one[0] = adc
                sink.onSamples(-1, one, 1, 0)
            }
            else -> {
                val seq = parts[0].toIntOrNull() ?: return
                val adc = parts[1].toIntOrNull() ?: return
                val lo = parts.getOrNull(2)?.toIntOrNull() ?: 0
                one[0] = adc
                sink.onSamples(seq and 0xFFFF, one, 1, lo and 3)
            }
        }
    }
}

/** Parser do modo binário da serial: `A5 5A len payload crc8`. */
class BinaryFrameParser(private val sink: SampleSink) {
    private val decoder = PacketDecoder(sink)
    private val frame = ByteArray(260)
    private var state = 0
    private var len = 0
    private var pos = 0
    var crcErrors = 0
        private set

    fun feed(data: ByteArray, n: Int = data.size) {
        for (i in 0 until n) {
            val b = data[i].toInt() and 0xFF
            when (state) {
                0 -> if (b == 0xA5) state = 1
                1 -> state = if (b == 0x5A) 2 else if (b == 0xA5) 1 else 0
                2 -> { len = b; pos = 0; state = if (len in 4..255) 3 else 0 }
                3 -> {
                    frame[pos++] = b.toByte()
                    if (pos == len) state = 4
                }
                4 -> {
                    if (SistoleProtocol.crc8(frame, 0, len) == b) decoder.decode(frame, 0, len)
                    else { crcErrors++; sink.onConsole("CRC inválido ($crcErrors)") }
                    state = 0
                }
            }
        }
    }
}
