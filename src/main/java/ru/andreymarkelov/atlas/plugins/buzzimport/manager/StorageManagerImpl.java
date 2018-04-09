package ru.andreymarkelov.atlas.plugins.buzzimport.manager;

import java.util.Objects;

import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;

public class StorageManagerImpl implements StorageManager {
    private final String PLUGIN_KEY = "BuzzImports";

    private final PluginSettings pluginSettings;

    public StorageManagerImpl(PluginSettingsFactory pluginSettingsFactory) {
        this.pluginSettings = pluginSettingsFactory.createSettingsForKey(PLUGIN_KEY);;
    }

    @Override
    public void setLastMapping(String lastMapping) {
        getPluginSettings().put("LAST_MAPPING", lastMapping);
    }

    @Override
    public String getLastMapping() {
        return Objects.toString(getPluginSettings().get("LAST_MAPPING"), "");
    }

    private synchronized PluginSettings getPluginSettings() {
        return pluginSettings;
    }
}
