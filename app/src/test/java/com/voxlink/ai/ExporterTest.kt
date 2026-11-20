package com.voxlink.ai

import com.voxlink.ai.data.Exporter
import com.voxlink.ai.model.Entry
import com.voxlink.ai.model.Lang
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExporterTest {
    @Test
    fun writesMinutes() {
        val now = System.currentTimeMillis()
        val entries = listOf(
            Entry(1, "设备安装已经完成，我们明天开始测试。", Lang.ZH, "A instalação do equipamento foi concluída, começamos os testes amanhã.", Lang.PT, now, "Online", "Shèbèi ānzhuāng yǐjīng wánchéng, wǒmen míngtiān kāishǐ cèshì.", null),
            Entry(2, "Precisamos ajustar o torque da parafusadeira <posto 3> & revisar a primeira peça.", Lang.PT, "我们需要调整电批的扭力并检查首件。", Lang.ZH, now + 4000, "No aparelho", null, "Wǒmen xūyào tiáozhěng diànpī de niǔlì bìng jiǎnchá shǒujiàn."),
        )
        val meta = Exporter.Meta("Ata — Visita fornecedor (teste)", "Everton (PT) · Li Wei (中文)", "LEO")
        val dir = File("build/ata-test").apply { mkdirs() }
        val x = File(dir, "ata-test.xlsx")
        val d = File(dir, "ata-test.docx")
        Exporter.writeXlsx(x, meta, entries)
        Exporter.writeDocx(d, meta, entries)
        assertTrue(x.length() > 1000)
        assertTrue(d.length() > 1000)
    }
}
