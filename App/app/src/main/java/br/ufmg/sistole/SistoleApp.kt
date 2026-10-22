package br.ufmg.sistole

import android.app.Application
import br.ufmg.sistole.data.SessionAnalysis
import br.ufmg.sistole.data.SessionMeta
import br.ufmg.sistole.data.SessionStore
import br.ufmg.sistole.data.SettingsRepository
import br.ufmg.sistole.engine.AcquisitionEngine
import br.ufmg.sistole.engine.Beeper
import br.ufmg.sistole.source.ConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Grafo de dependências do app: um motor, uma conexão e o armazenamento, vivos enquanto o processo existir. */
class SistoleApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var settings: SettingsRepository
        private set
    lateinit var engine: AcquisitionEngine
        private set
    lateinit var connection: ConnectionManager
        private set
    lateinit var sessions: SessionStore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsRepository(this)
        engine = AcquisitionEngine(settings, scope, Beeper())
        connection = ConnectionManager(this, engine, settings, scope)
        sessions = SessionStore(this)
        scope.launch { sessions.refresh() }
    }

    /** Encerra a gravação, reprocessa o arquivo para o resumo e salva. */
    suspend fun finishRecording(): SessionMeta? {
        val meta = engine.stopRecording() ?: return null
        return withContext(Dispatchers.Default) {
            val adc = sessions.loadSamples(meta.id)
            val a = SessionAnalysis(meta, adc)
            val done = meta.copy(summary = a.summary(meta.goodSeconds, meta.totalSeconds), preview = a.preview())
            sessions.save(done)
            done
        }
    }

    companion object {
        lateinit var instance: SistoleApp
            private set
    }
}
