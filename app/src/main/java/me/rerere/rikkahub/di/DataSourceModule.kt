package me.rerere.rikkahub.di

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.HttpHeaders
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderManager
// v274：三套官方账号 OAuth 登录（照搬自 ExTV rikkahub-agent）
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.codex.CodexAccountRepository
import me.rerere.rikkahub.data.codex.CodexCredentialStore
import me.rerere.rikkahub.data.codex.CodexOAuthManager
import me.rerere.rikkahub.data.codex.CodexProvider
import me.rerere.rikkahub.data.gemini.GeminiAccountRepository
import me.rerere.rikkahub.data.gemini.GeminiCredentialStore
import me.rerere.rikkahub.data.gemini.GeminiOAuthManager
import me.rerere.rikkahub.data.gemini.GeminiProvider
import me.rerere.rikkahub.data.grok.GrokAccountRepository
import me.rerere.rikkahub.data.grok.GrokCredentialStore
import me.rerere.rikkahub.data.grok.GrokOAuthManager
import me.rerere.rikkahub.data.grok.GrokProvider
import org.koin.core.qualifier.named
import me.rerere.common.http.AcceptLanguageBuilder
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.ai.AIRequestInterceptor
import me.rerere.rikkahub.data.ai.RequestLoggingInterceptor
import me.rerere.rikkahub.data.ai.transformers.AssistantTemplateLoader
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.TranslationHandler
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.api.RikkaHubAPI
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.sync.BackupManager
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.network.SettingsProxySelector
import me.rerere.rikkahub.data.network.SettingsProxyAuthenticator
import me.rerere.rikkahub.data.network.SettingsSocks5Authenticator
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import me.rerere.search.SearchService
import me.rerere.rikkahub.data.sync.S3Sync
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

val dataSourceModule = module {
    single {
        SettingsStore(context = get(), scope = get())
    }

    single {
        val context: Context = get()
        AppDatabaseFactory.create(context)
    }

    single {
        AssistantTemplateLoader(settingsStore = get())
    }

    single {
        PebbleEngine.Builder()
            .loader(get<AssistantTemplateLoader>())
            .defaultLocale(Locale.getDefault())
            .autoEscaping(false)
            .build()
    }

    single { TemplateTransformer(engine = get(), settingsStore = get()) }

    single {
        get<AppDatabase>().conversationDao()
    }

    single {
        get<AppDatabase>().memoryDao()
    }

    single {
        get<AppDatabase>().genMediaDao()
    }

    single {
        get<AppDatabase>().messageNodeDao()
    }

    single {
        get<AppDatabase>().managedFileDao()
    }

    single {
        get<AppDatabase>().favoriteDao()
    }

    single {
        get<AppDatabase>().workspaceDao()
    }

    single {
        get<AppDatabase>().folderDao()
    }

    single {
        MessageFtsManager(get())
    }

    single { McpManager(settingsStore = get(), appScope = get(), filesManager = get()) }

    single {
        GenerationLoop(
            context = get(),
            providerManager = get(),
            json = get(),
        )
    }

    single {
        TranslationHandler(providerManager = get())
    }

    single<OkHttpClient> {
        val settingsStore: SettingsStore = get()
        val acceptLang = AcceptLanguageBuilder.fromAndroid(get())
            .build()
        java.net.Authenticator.setDefault(SettingsSocks5Authenticator(settingsStore))
        val initialNetworkSetting = settingsStore.settingsFlow.value.networkSetting
        val appliedProxySetting = AtomicReference(
            Triple(
                initialNetworkSetting.proxyUrl,
                initialNetworkSetting.proxyUsername,
                initialNetworkSetting.proxyPassword,
            )
        )
        lateinit var client: OkHttpClient
        client = OkHttpClient.Builder()
            .proxySelector(SettingsProxySelector(settingsStore))
            .proxyAuthenticator(SettingsProxyAuthenticator(settingsStore))
            .connectTimeout(20, TimeUnit.SECONDS)
            // AI 压缩采用流式响应；允许长提示词和长摘要最多等待 30 分钟。
            .readTimeout(30, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val networkSetting = settingsStore.settingsFlow.value.networkSetting
                val currentProxySetting = Triple(
                    networkSetting.proxyUrl,
                    networkSetting.proxyUsername,
                    networkSetting.proxyPassword,
                )
                if (appliedProxySetting.getAndSet(currentProxySetting) != currentProxySetting) {
                    client.connectionPool.evictAll()
                }

                val originalRequest = chain.request()
                val requestBuilder = originalRequest.newBuilder()
                    .addHeader(HttpHeaders.AcceptLanguage, acceptLang)

                if (originalRequest.header(HttpHeaders.UserAgent) == null) {
                    val userAgent = settingsStore.settingsFlow.value.networkSetting.userAgent
                        .trim()
                        .ifEmpty { "RikkaHub-Android/${BuildConfig.VERSION_NAME}" }
                    requestBuilder.addHeader(HttpHeaders.UserAgent, userAgent)
                }

                chain.proceed(requestBuilder.build())
            }
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val contentTypeHeader = request.header("Content-Type")
                if (
                    contentTypeHeader != null &&
                    contentTypeHeader.contains(";") &&
                    contentTypeHeader.substringBefore(";").trim().equals("application/json", ignoreCase = true)
                ) {
                    chain.proceed(
                        request.newBuilder()
                            .header("Content-Type", contentTypeHeader.substringBefore(";").trim())
                            .build()
                    )
                } else {
                    chain.proceed(request)
                }
            }
            .addNetworkInterceptor(RequestLoggingInterceptor())
            .addInterceptor(AIRequestInterceptor())
            .addInterceptor(HttpLoggingInterceptor().apply {
                redactHeader("Proxy-Authorization")
                level = HttpLoggingInterceptor.Level.HEADERS
            })
            .build()
        client.also { SearchService.init(it, get()) }
    }

    // v274：三套官方账号 OAuth 的专用干净客户端（无默认 UA/日志拦截器，
    // 伪装头在各 Provider 请求层按需添加；read 10min 对长 SSE 必要）——照搬 ExTV
    single<OkHttpClient>(named("codex")) {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    single<OkHttpClient>(named("grok")) {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    single<OkHttpClient>(named("gemini")) {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    single {
        GrokAccountRepository(
            store = GrokCredentialStore(context = get(), json = get()),
            client = get(named("grok")),
            json = get(),
        )
    }

    single {
        GrokOAuthManager(
            context = get(),
            scope = get<AppScope>(),
            client = get(named("grok")),
            repository = get(),
            json = get(),
        )
    }

    single {
        GeminiAccountRepository(
            store = GeminiCredentialStore(context = get(), json = get()),
            client = get(named("gemini")),
            json = get(),
        )
    }

    single {
        GeminiOAuthManager(
            context = get(),
            scope = get<AppScope>(),
            client = get(named("gemini")),
            repository = get(),
        )
    }

    single {
        CodexAccountRepository(
            store = CodexCredentialStore(context = get(), json = get()),
            client = get(named("codex")),
            json = get(),
        )
    }

    single {
        CodexOAuthManager(
            context = get(),
            scope = get<AppScope>(),
            client = get(named("codex")),
            repository = get(),
        )
    }

    // v274：ProviderManager 在官方三家之外注册三套账号登录 provider（照搬 ExTV，
    // 不含其本地模型注册）。官方三家仍走 ProviderManager init 里的 ReasoningFallbackProvider 包裹。
    single {
        val codexRepository: CodexAccountRepository = get()
        val grokRepository: GrokAccountRepository = get()
        val geminiRepository: GeminiAccountRepository = get()
        val json: Json = get()
        ProviderManager(client = get(), context = get()).also { pm ->
            pm.registerProvider(
                "codex",
                CodexProvider(
                    client = get(named("codex")),
                    repository = codexRepository,
                    json = json,
                    scope = get<AppScope>(),
                )
            )
            pm.registerProvider(
                "grok",
                GrokProvider(
                    client = get(named("grok")),
                    repository = grokRepository,
                    json = json,
                    scope = get<AppScope>(),
                )
            )
            pm.registerProvider(
                "gemini_oauth",
                GeminiProvider(
                    client = get(named("gemini")),
                    repository = geminiRepository,
                    json = json,
                )
            )
        }
    }

    single { BackupManager(context = get(), database = get(), settingsStore = get(), json = get()) }

    single {
        WebDavSync(
            backupManager = get(),
            context = get(),
            httpClient = get()
        )
    }

    single<HttpClient> {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(20, TimeUnit.SECONDS)
                    readTimeout(10, TimeUnit.MINUTES)
                    writeTimeout(120, TimeUnit.SECONDS)
                    followSslRedirects(true)
                    followRedirects(true)
                    retryOnConnectionFailure(true)
                }
            }
        }
    }

    single {
        S3Sync(
            backupManager = get(),
            context = get(),
            httpClient = get()
        )
    }

    single<Retrofit> {
        Retrofit.Builder()
            .baseUrl("https://api.rikka-ai.com")
            .addConverterFactory(get<Json>().asConverterFactory("application/json; charset=UTF8".toMediaType()))
            .build()
    }

    single<RikkaHubAPI> {
        get<Retrofit>().create(RikkaHubAPI::class.java)
    }
}
