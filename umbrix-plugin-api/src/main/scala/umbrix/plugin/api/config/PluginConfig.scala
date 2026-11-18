package umbrix.plugin.api.config

/**
 * Primitive data type specification for declarative plugin configuration settings.
 */
enum PluginSettingType extends java.lang.Enum[PluginSettingType] {
  case STRING
  case SECRET
  case INTEGER
  case BOOLEAN
  case FILE_PATH
  case CHOICE
}

/**
 * Declarative setting field automatically rendered in `PluginsSettingsTab`
 * and persisted in `~/.umbrix/plugins/<pluginId>/config.json`.
 */
final case class PluginSettingDescriptor(
  key: String,
  label: String,
  description: String,
  settingType: PluginSettingType,
  defaultValue: String,
  choices: Array[String] = Array.empty
)

/**
 * Thread-safe configuration store persisted at `~/.umbrix/plugins/<pluginId>/config.json`.
 */
trait PluginConfigStore {
  def getString(key: String, defaultValue: String = ""): String
  def getInt(key: String, defaultValue: Int = 0): Int
  def getBoolean(key: String, defaultValue: Boolean = false): Boolean
  def setString(key: String, value: String): Unit
  def setInt(key: String, value: Int): Unit
  def setBoolean(key: String, value: Boolean): Unit
  def save(): Unit
}
