package de.levingamer8.modlauncher.service;

import de.levingamer8.modlauncher.core.ProfileStore;

import java.nio.file.Path;
import java.util.List;

public class ProfileService {

    private final ProfileStore store;

    public ProfileService(ProfileStore store) {
        this.store = store;
    }

    public List<ProfileStore.Profile> loadProfiles() {
        return store.loadProfiles();
    }

    public void saveOrUpdate(ProfileStore.Profile p) {
        store.saveOrUpdateProfile(p);
    }

    public void deleteByName(String name) {
        store.deleteProfile(name);
    }

    public Path baseDir() { return store.baseDir(); }
    public Path sharedRoot() { return store.sharedRoot(); }

    public Path instanceDir(String profileName) { return store.instanceDir(profileName); }
    public Path instanceGameDir(String profileName) { return store.instanceGameDir(profileName); }
    public Path instanceRuntimeDir(String profileName) { return store.instanceRuntimeDir(profileName); }
}
