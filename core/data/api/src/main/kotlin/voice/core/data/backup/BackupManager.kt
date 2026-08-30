package voice.core.data.backup

public interface BackupManager {
  public suspend fun createBackup(): VoiceBackup
  public suspend fun restoreBackup(backup: VoiceBackup): ImportResult
  public suspend fun restoreBackupFromJson(jsonString: String): ImportResult
  public fun serializeBackup(backup: VoiceBackup): String
}

