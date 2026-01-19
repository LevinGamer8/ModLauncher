package de.levingamer8.modlauncher.ui.dialogs;

import de.levingamer8.modlauncher.core.ProfileStore;

public record ProfileDialogResult(
        ProfileStore.Profile profile,
        String oldNameOrNull
) {}
