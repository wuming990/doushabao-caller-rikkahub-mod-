package me.rerere.rikkahub.di

import android.content.Context
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.LocalStorageManager
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.files.OfficialImportSession
import me.rerere.rikkahub.data.files.OfficialWorkspaceMigrationManager
import me.rerere.rikkahub.data.files.SharedStorageOfficialSource
import me.rerere.rikkahub.data.files.WorkspaceRepositoryDestination
import me.rerere.rikkahub.data.files.SharedStorageManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceManager
import org.koin.dsl.module
import java.io.File

val repositoryModule = module {
    single {
        ConversationRepository(get(), get(), get(), get(), get(), get())
    }

    single {
        FolderRepository(get(), get())
    }

    single {
        MemoryRepository(get())
    }

    single {
        GenMediaRepository(get())
    }

    single {
        FilesRepository(get())
    }

    single {
        LocalStorageManager(get())
    }

    single {
        FavoriteRepository(get())
    }

    single {
        val context: Context = get()
        WorkspaceManager(
            baseDir = File(context.filesDir, "workspaces"),
            shellRunner = ProotShellRunner(
                nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
            ),
            // 同一份挂载表既用于 PRoot 的 -b 参数, 也用于文件工具的路径解析, 避免两处漂移
            bindMounts = listOf(
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() },
                    target = "/skills",
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() },
                    target = "/tool_outputs",
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.UPLOAD).apply { mkdirs() },
                    target = "/upload",
                ),
            ),
        )
    }

    single {
        RootfsInstaller(get())
    }

    single {
        WorkspaceRepository(get(), get(), get(), get())
    }

    // 迁移读取/写入适配器必须是共享单例：会话创建目标工作区后，迁移热路径复用同一份 root 缓存。
    // 显式按具体类解析，避免 v188 那种对未 bind 接口使用裸 get() 的 Koin 崩溃。
    single { SharedStorageOfficialSource(get()) }
    single { WorkspaceRepositoryDestination(get(), get()) }

    single {
        OfficialWorkspaceMigrationManager(
            get<SharedStorageOfficialSource>(),
            get<WorkspaceRepositoryDestination>(),
        )
    }

    single {
        OfficialImportSession(
            get(),
            get<AppScope>(),
            get(),
            get<WorkspaceRepositoryDestination>(),
            get(),
        )
    }

    single {
        FilesManager(get(), get(), get())
    }

    single {
        SharedStorageManager(get())
    }

    single {
        SkillManager(get(), get())
    }
}
