package app.aisupervisor.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.aisupervisor.model.Integration
import app.aisupervisor.model.MonitorStatus
import app.aisupervisor.model.Project
import app.aisupervisor.model.TimelineEvent

class SupervisorDb(context: Context) : SQLiteOpenHelper(context, "ai_supervisor.db", null, 1) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE projects (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                repo TEXT NOT NULL,
                branch TEXT NOT NULL DEFAULT 'main',
                enabled INTEGER NOT NULL DEFAULT 1,
                chat_pinger INTEGER NOT NULL DEFAULT 0,
                warning_seconds INTEGER NOT NULL DEFAULT 180,
                stalled_seconds INTEGER NOT NULL DEFAULT 300,
                hard_seconds INTEGER NOT NULL DEFAULT 600,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE integrations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id INTEGER NOT NULL,
                type TEXT NOT NULL,
                label TEXT NOT NULL,
                endpoint TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                config_json TEXT NOT NULL DEFAULT '{}',
                FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id INTEGER NOT NULL,
                ts INTEGER NOT NULL,
                source TEXT NOT NULL,
                status TEXT NOT NULL,
                title TEXT NOT NULL,
                detail TEXT NOT NULL,
                fingerprint TEXT NOT NULL,
                FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_events_project_ts ON events(project_id, ts DESC)")
        db.execSQL("CREATE INDEX idx_integrations_project ON integrations(project_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun ensureSeedProject(): Long {
        readableDatabase.rawQuery("SELECT id FROM projects LIMIT 1", null).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return addProject("Время Ходом", "fowov2-jpg/676017", "main")
    }

    fun addProject(name: String, repo: String, branch: String): Long {
        val values = ContentValues().apply {
            put("name", name.trim())
            put("repo", repo.trim())
            put("branch", branch.trim().ifBlank { "main" })
            put("enabled", 1)
            put("chat_pinger", 0)
            put("warning_seconds", 180)
            put("stalled_seconds", 300)
            put("hard_seconds", 600)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insertOrThrow("projects", null, values)
    }

    fun updateProject(project: Project) {
        val values = ContentValues().apply {
            put("name", project.name)
            put("repo", project.repo)
            put("branch", project.branch)
            put("enabled", if (project.enabled) 1 else 0)
            put("chat_pinger", if (project.chatPinger) 1 else 0)
            put("warning_seconds", project.warningSeconds)
            put("stalled_seconds", project.stalledSeconds)
            put("hard_seconds", project.hardSeconds)
        }
        writableDatabase.update("projects", values, "id=?", arrayOf(project.id.toString()))
    }

    fun deleteProject(id: Long) {
        writableDatabase.delete("projects", "id=?", arrayOf(id.toString()))
    }

    fun getProject(id: Long): Project? = readableDatabase.rawQuery(
        "SELECT id,name,repo,branch,enabled,chat_pinger,warning_seconds,stalled_seconds,hard_seconds,created_at FROM projects WHERE id=?",
        arrayOf(id.toString())
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else cursor.toProject()
    }

    fun listProjects(): List<Project> = buildList {
        readableDatabase.rawQuery(
            "SELECT id,name,repo,branch,enabled,chat_pinger,warning_seconds,stalled_seconds,hard_seconds,created_at FROM projects ORDER BY created_at ASC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) add(cursor.toProject())
        }
    }

    fun listEnabledProjects(): List<Project> = listProjects().filter { it.enabled }

    fun addIntegration(projectId: Long, type: String, label: String, endpoint: String, configJson: String = "{}"): Long {
        val values = ContentValues().apply {
            put("project_id", projectId)
            put("type", type.trim().uppercase())
            put("label", label.trim())
            put("endpoint", endpoint.trim())
            put("enabled", 1)
            put("config_json", configJson.ifBlank { "{}" })
        }
        return writableDatabase.insertOrThrow("integrations", null, values)
    }

    fun updateIntegration(integration: Integration) {
        val values = ContentValues().apply {
            put("type", integration.type)
            put("label", integration.label)
            put("endpoint", integration.endpoint)
            put("enabled", if (integration.enabled) 1 else 0)
            put("config_json", integration.configJson)
        }
        writableDatabase.update("integrations", values, "id=?", arrayOf(integration.id.toString()))
    }

    fun deleteIntegration(id: Long) {
        writableDatabase.delete("integrations", "id=?", arrayOf(id.toString()))
    }

    fun listIntegrations(projectId: Long): List<Integration> = buildList {
        readableDatabase.rawQuery(
            "SELECT id,project_id,type,label,endpoint,enabled,config_json FROM integrations WHERE project_id=? ORDER BY id ASC",
            arrayOf(projectId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                add(
                    Integration(
                        id = cursor.getLong(0),
                        projectId = cursor.getLong(1),
                        type = cursor.getString(2),
                        label = cursor.getString(3),
                        endpoint = cursor.getString(4),
                        enabled = cursor.getInt(5) != 0,
                        configJson = cursor.getString(6)
                    )
                )
            }
        }
    }

    fun addEventIfChanged(
        projectId: Long,
        source: String,
        status: MonitorStatus,
        title: String,
        detail: String,
        fingerprint: String
    ): Boolean {
        readableDatabase.rawQuery(
            "SELECT fingerprint FROM events WHERE project_id=? AND source=? ORDER BY ts DESC,id DESC LIMIT 1",
            arrayOf(projectId.toString(), source)
        ).use { cursor ->
            if (cursor.moveToFirst() && cursor.getString(0) == fingerprint) return false
        }
        val values = ContentValues().apply {
            put("project_id", projectId)
            put("ts", System.currentTimeMillis())
            put("source", source)
            put("status", status.name)
            put("title", title)
            put("detail", detail)
            put("fingerprint", fingerprint)
        }
        writableDatabase.insertOrThrow("events", null, values)
        trimEvents(projectId)
        return true
    }

    fun listEvents(projectId: Long, limit: Int = 100): List<TimelineEvent> = buildList {
        readableDatabase.rawQuery(
            "SELECT id,project_id,ts,source,status,title,detail,fingerprint FROM events WHERE project_id=? ORDER BY ts DESC,id DESC LIMIT ?",
            arrayOf(projectId.toString(), limit.coerceIn(1, 500).toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                add(
                    TimelineEvent(
                        id = cursor.getLong(0),
                        projectId = cursor.getLong(1),
                        timestamp = cursor.getLong(2),
                        source = cursor.getString(3),
                        status = MonitorStatus.from(cursor.getString(4)),
                        title = cursor.getString(5),
                        detail = cursor.getString(6),
                        fingerprint = cursor.getString(7)
                    )
                )
            }
        }
    }

    private fun trimEvents(projectId: Long) {
        writableDatabase.execSQL(
            "DELETE FROM events WHERE project_id=? AND id NOT IN (SELECT id FROM events WHERE project_id=? ORDER BY ts DESC,id DESC LIMIT 1000)",
            arrayOf(projectId, projectId)
        )
    }

    private fun android.database.Cursor.toProject() = Project(
        id = getLong(0),
        name = getString(1),
        repo = getString(2),
        branch = getString(3),
        enabled = getInt(4) != 0,
        chatPinger = getInt(5) != 0,
        warningSeconds = getInt(6),
        stalledSeconds = getInt(7),
        hardSeconds = getInt(8),
        createdAt = getLong(9)
    )
}
