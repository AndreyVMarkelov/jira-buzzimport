package ru.andreymarkelov.atlas.plugins.buzzimport.manager;

public interface StorageManager {
    void setLastMapping(String lastMapping);
    String getLastMapping();
}
