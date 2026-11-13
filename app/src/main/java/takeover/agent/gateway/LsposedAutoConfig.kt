package takeover.agent.gateway

import android.database.sqlite.SQLiteDatabase
import android.os.Process
import org.json.JSONObject
import java.io.File

/**
 * LSPosed 作用域全自动配置（完全体核心）。
 *
 * 流程（全部 root 经 daemon 网关，用户零操作，只需最后重启一次）：
 *   1. 收集第三方包名（pm list packages -3）
 *   2. kill lspd（防 WAL 竞态）→ 拷 modules_config.db 三件套到 app 私有目录
 *   3. app 内 SQLiteDatabase 写入：
 *        modules_state: 启用 takeover.agent
 *        scope: android + system + 全部第三方包
 *   4. 再杀一次 lspd → 拷回 /data/adb/lspd/config/ → 还原 root:root 0600
 *   5. 写成功标记；返回 rebootNeeded=true（重启后 LSPosed 按新配置加载）
 *
 * 失败安全：patch 前保留 .orig 副本；任何异常都会把原库拷回（LSPosed 不被玩坏）。
 */
object LsposedAutoConfig {

    private const val MOD = "takeover.agent"
    private const val LSPD_DIR = "/data/adb/lspd/config"
    private const val DB = "modules_config.db"
    private const val MARK = ".lsposed_configured"

    data class Result(val configured: Boolean, val rebootNeeded: Boolean, val detail: String)

    fun alreadyConfigured(): Boolean = File(appFiles(), MARK).exists()

    private fun appFiles(): File =
        GatewayService.appContext?.filesDir ?: File("/data/data/takeover.agent/files")

    /** 执行全自动配置（幂等；已配置直接返回） */
    fun run(): Result {
        if (alreadyConfigured()) return Result(false, false, "already configured")
        val ctx = GatewayService.appContext ?: return Result(false, false, "no context")
        val files = ctx.filesDir

        try {
            // 1) 收集第三方包名
            val pkgs = collectThirdPartyPkgs()
            if (pkgs.isEmpty()) return Result(false, false, "no packages collected")

            // 2) 杀 lspd + 拷出三件套（root）
            val uid = Process.myUid()
            RootProvider.exec(
                "pidof lspd | tr ' ' '\\n' | while read p; do kill -9 $p 2>/dev/null; done; " +
                "sleep 0.4; cp $LSPD_DIR/$DB $LSPD_DIR/$DB-wal $LSPD_DIR/$DB-shm ${files.absolutePath}/ 2>/dev/null; " +
                "chown $uid:$uid ${files.absolutePath}/$DB*", 10000
            )

            val dbFile = File(files, DB)
            if (!dbFile.exists()) return Result(false, false, "db copy failed (lspd dir unreachable?)")
            // 备份原件（失败回滚用）
            val orig = File(files, "$DB.orig")
            if (!orig.exists()) dbFile.copyTo(orig, overwrite = true)

            // 3) 写库
            val stats = patchDb(dbFile.absolutePath, pkgs, ctx.applicationInfo.sourceDir)

            // 4) 拷回（再杀一次防 lspd 复活写入）+ 还原权限
            RootProvider.exec(
                "pidof lspd | tr ' ' '\\n' | while read p; do kill -9 $p 2>/dev/null; done; " +
                "cp ${files.absolutePath}/$DB ${files.absolutePath}/$DB-wal ${files.absolutePath}/$DB-shm $LSPD_DIR/ && " +
                "chown root:root $LSPD_DIR/$DB $LSPD_DIR/$DB-wal $LSPD_DIR/$DB-shm && " +
                "chmod 600 $LSPD_DIR/$DB $LSPD_DIR/$DB-wal $LSPD_DIR/$DB-shm", 10000
            )

            // 5) 标记成功
            File(files, MARK).writeText("ok ${System.currentTimeMillis()} scope=${pkgs.size + 2}")
            return Result(true, true, "configured with ${pkgs.size} pkgs + framework")
        } catch (t: Throwable) {
            // 失败回滚：把 .orig 拷回系统位置，LSPosed 保持原样
            try {
                val orig = File(files, "$DB.orig")
                if (orig.exists()) {
                    orig.copyTo(File(files, DB), overwrite = true)
                    RootProvider.exec(
                        "cp ${files.absolutePath}/$DB* $LSPD_DIR/ && chown root:root $LSPD_DIR/$DB* " +
                        "&& chmod 600 $LSPD_DIR/$DB*", 8000
                    )
                }
            } catch (_: Throwable) { }
            return Result(false, false, "failed: ${t.message}")
        }
    }

    private fun collectThirdPartyPkgs(): List<String> {
        val (rc, out) = run {
            val r = RootProvider.exec("pm list packages -3", 8000)
            r.code to r.out
        }
        if (rc != 0) return emptyList()
        return out.lines()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }
            .filter { it.isNotEmpty() && it != MOD }
            .distinct()
            .sorted()
    }

    /** 真正的写库（app 私有副本上操作） */
    private fun patchDb(path: String, pkgs: List<String>, apkPath: String): JSONObject {
        val db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE)
        db.use {
            it.beginTransaction()
            try {
                // 模块注册（幂等）
                it.execSQL(
                    "INSERT OR IGNORE INTO modules(module_pkg_name, apk_path) VALUES(?,?)",
                    arrayOf(MOD, apkPath)
                )
                // 启用模块（对应管理器"启用模块"开关）
                it.execSQL(
                    "INSERT OR REPLACE INTO modules_state(module_pkg_name, user_id, enabled, scope_request_blocked) " +
                    "VALUES(?,0,1,0)", arrayOf(MOD)
                )
                // 作用域：framework + 全部第三方
                it.execSQL("DELETE FROM scope WHERE module_pkg_name = ?", arrayOf(MOD))
                val st = it.compileStatement("INSERT OR IGNORE INTO scope(module_pkg_name, app_pkg_name, user_id) VALUES(?,?,0)")
                for (p in listOf("android", "system") + pkgs) {
                    st.bindString(1, MOD); st.bindString(2, p)
                    st.executeInsert(); st.clearBindings()
                }
                it.setTransactionSuccessful()
            } finally {
                it.endTransaction()
            }
        }
        return JSONObject().put("pkgs", pkgs.size).put("apk", apkPath)
    }
}
