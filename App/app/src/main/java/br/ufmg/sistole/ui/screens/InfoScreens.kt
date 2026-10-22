package br.ufmg.sistole.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import br.ufmg.sistole.BuildConfig
import br.ufmg.sistole.dsp.SyntheticEcg
import br.ufmg.sistole.ui.components.AppBar
import br.ufmg.sistole.ui.components.BannerLevel
import br.ufmg.sistole.ui.components.EcgStrip
import br.ufmg.sistole.ui.components.RowDivider
import br.ufmg.sistole.ui.components.SBanner
import br.ufmg.sistole.ui.components.SButton
import br.ufmg.sistole.ui.components.SCard
import br.ufmg.sistole.ui.components.SIconButton
import br.ufmg.sistole.ui.components.SList
import br.ufmg.sistole.ui.components.SRow
import br.ufmg.sistole.ui.components.SectionLabel
import br.ufmg.sistole.ui.components.Sub
import br.ufmg.sistole.ui.components.TraceBox
import br.ufmg.sistole.ui.theme.S
import br.ufmg.sistole.ui.theme.SIcons
import br.ufmg.sistole.ui.theme.SType

private val TEAM = listOf(
    "Arnaldo Kokke de Brito",
    "Gabriel Lyan Barbosa de Assis",
    "Henrique Marques Cruz",
    "Lara Strutz Carvalho",
    "Otavio Henrique Alves da Silva",
    "Rian Rero Lopes Jerico Vieira",
)

private val SAFETY = listOf(
    "Uso didático. Não é equipamento médico e não serve para diagnóstico.",
    "Com eletrodos em alguém, o celular e o aparelho funcionam só na bateria. Nada ligado na tomada.",
    "As detecções automáticas (FC, eventos) são aproximações e podem errar.",
    "Retire os eletrodos se a pele irritar.",
)

@Composable
fun DisclaimerScreen(onAccept: () -> Unit) {
    val c = S.c
    val strip = remember {
        val e = SyntheticEcg(bpm = 68.0, noise = 0.0, seed = 5)
        FloatArray(1800) { e.value(it / 500.0).toFloat() }
    }
    Column(
        Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Sístole", style = SType.display, color = c.ink)
        Sub("Sinal limpo, leitura imediata. O app do ECG da Instrumentação Biomédica · UFMG.", maxLines = 3)
        TraceBox(Modifier.fillMaxWidth().height(96.dp)) { EcgStrip(strip, 500.0, cal = true, gainMmMv = 5f, base = 0.72f) }
        SBanner(BannerLevel.WARN, "Leia antes de começar.", title = "Aviso de uso", icon = SIcons.warn)
        SList {
            SAFETY.forEachIndexed { i, t ->
                if (i > 0) RowDivider()
                SRow(t, lead = if (i == 1) SIcons.bolt else SIcons.info)
            }
        }
        SButton("Entendi", onAccept, Modifier.fillMaxWidth())
    }
}

@Composable
fun AboutScreen(nav: NavHostController) {
    val c = S.c
    Column(Modifier.fillMaxSize()) {
        AppBar(title = "Sobre", onBack = { nav.popBackStack() })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SCard {
                Text("Sístole", style = SType.display, color = c.ink)
                Sub("Versão ${BuildConfig.VERSION_NAME} · EEE025 Instrumentação Biomédica · Escola de Engenharia da UFMG", maxLines = 3)
            }
            SBanner(BannerLevel.WARN, SAFETY.joinToString(" "), title = "Uso didático. Não é equipamento médico.")
            SectionLabel("Cadeia de aquisição")
            SCard {
                Text(
                    "Eletrodos → INA122 → passa-baixas 160 Hz, notch 59–61 e 119–121 Hz, bloqueio de DC → ganho e offset para 0–3,3 V → ADC do ESP32 → Bluetooth LE ou USB serial → filtros digitais, detecção Pan-Tompkins e HRV no app.",
                    style = SType.body, color = c.ink,
                )
            }
            SectionLabel("Equipe")
            SList {
                TEAM.forEachIndexed { i, n ->
                    if (i > 0) RowDivider()
                    SRow(n, lead = SIcons.person)
                }
            }
            SectionLabel("Protocolo")
            SCard {
                Text(
                    "Serial (texto): uma linha por amostra, “seq,adc,lo”. Metadados em linhas com #: “#fs=500;bits=12;vref=3.3”.\nBLE: serviço 8a3e0001-…, notify 8a3e0002 com pacotes u16 seq · u8 flags · u8 n · n×u16 amostras.",
                    style = SType.dataSm, color = c.inkMuted,
                )
            }
            Sub("Fontes IBM Plex Sans, IBM Plex Mono e Barlow Condensed, licença SIL Open Font License 1.1.", maxLines = 2)
        }
    }
}

@Composable
fun ProfilesScreen(nav: NavHostController) {
    val s by app.settings.flow.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        AppBar(title = "Voluntários", onBack = { nav.popBackStack() })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Sub("Nomes que aparecem para escolher ao gravar. Ficam só neste celular.", Modifier.padding(horizontal = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { SField(name, { name = it }, "Nome ou apelido") }
                SButton("Adicionar", {
                    val n = name.trim()
                    if (n.isNotEmpty()) app.settings.update { it.copy(profiles = (it.profiles + n).distinct()) }
                    name = ""
                }, small = true, enabled = name.isNotBlank())
            }
            if (s.profiles.isEmpty()) {
                SList { SRow("Nenhum perfil", detail = "Os nomes usados ao gravar entram aqui sozinhos.") }
            } else {
                SList {
                    s.profiles.forEachIndexed { i, p ->
                        if (i > 0) RowDivider()
                        SRow(p, lead = SIcons.person) {
                            SIconButton(SIcons.trash, "Remover $p", { app.settings.update { it.copy(profiles = it.profiles - p, lastProfile = if (it.lastProfile == p) null else it.lastProfile) } }, flat = true)
                        }
                    }
                }
            }
        }
    }
}
