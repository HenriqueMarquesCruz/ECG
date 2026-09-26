package br.ufmg.sistole

import br.ufmg.sistole.source.BinaryFrameParser
import br.ufmg.sistole.source.CsvLineParser
import br.ufmg.sistole.source.PacketDecoder
import br.ufmg.sistole.source.SampleSink
import br.ufmg.sistole.source.SistoleProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolTest {
    private class Rec : SampleSink {
        val samples = mutableListOf<Triple<Int, Int, Int>>()
        var meta = mapOf<String, String>()
        var battery = -1
        override fun onSamples(seq: Int, adc: IntArray, count: Int, loFlags: Int) {
            for (i in 0 until count) samples += Triple(if (seq < 0) -1 else (seq + i) and 0xFFFF, adc[i], loFlags)
        }
        override fun onMeta(meta: Map<String, String>) { this.meta = meta }
        override fun onBattery(mv: Int) { battery = mv }
        override fun onConsole(line: String) {}
        override fun onPacket(bytes: Int) {}
    }

    private fun packet(seq: Int, flags: Int, vararg adc: Int, bat: Int? = null): ByteArray {
        val f = flags or if (bat != null) SistoleProtocol.FLAG_BATTERY else 0
        val out = ArrayList<Byte>()
        out += (seq and 0xFF).toByte(); out += (seq shr 8).toByte(); out += f.toByte(); out += adc.size.toByte()
        adc.forEach { out += (it and 0xFF).toByte(); out += (it shr 8).toByte() }
        if (bat != null) { out += (bat and 0xFF).toByte(); out += (bat shr 8).toByte() }
        return out.toByteArray()
    }

    @Test
    fun csvParsesAllForms() {
        val r = Rec()
        val p = CsvLineParser(r)
        p.feed("12,20\r\n#fs=500;bits=12;fw=sistole-fw 0.1.0\n1523,2087,0\n1524,2090,2\n2100\n\nlixo\n#bat=3910\n".toByteArray())
        assertEquals(listOf(Triple(1523, 2087, 0), Triple(1524, 2090, 2), Triple(-1, 2100, 0), Triple(12, 20, 0)).toSet(), r.samples.toSet())
        assertEquals(3910, r.battery)
    }

    @Test
    fun csvHandlesSplitChunks() {
        val r = Rec()
        val p = CsvLineParser(r)
        p.feed("10,2".toByteArray()); p.feed("048,0\n11,20".toByteArray()); p.feed("50,1\n".toByteArray())
        assertEquals(listOf(Triple(10, 2048, 0), Triple(11, 2050, 1)), r.samples)
    }

    @Test
    fun packetDecodes() {
        val r = Rec()
        PacketDecoder(r).decode(packet(65535, 1, 100, 200, 4095, bat = 3800))
        assertEquals(listOf(Triple(65535, 100, 1), Triple(0, 200, 1), Triple(1, 4095, 1)), r.samples)
        assertEquals(3800, r.battery)
    }

    @Test
    fun binaryFramesWithCrcAndResync() {
        val r = Rec()
        val p = BinaryFrameParser(r)
        val pk = packet(7, 0, 1, 2, 3)
        val frame = byteArrayOf(0xA5.toByte(), 0x5A, pk.size.toByte()) + pk + SistoleProtocol.crc8(pk, 0, pk.size).toByte()
        val bad = frame.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        p.feed(byteArrayOf(1, 2, 0xA5.toByte()) + bad + frame)
        assertEquals(listOf(Triple(7, 1, 0), Triple(8, 2, 0), Triple(9, 3, 0)), r.samples)
        assertEquals(1, p.crcErrors)
    }

    @Test
    fun metaParse() {
        val m = SistoleProtocol.parseMeta("fs=500;bits=12;vref=3.3;fw=sistole-fw 0.1.0;lo=1")
        assertEquals("500", m["fs"]); assertEquals("sistole-fw 0.1.0", m["fw"]); assertEquals("1", m["lo"])
    }
}
