package de.levingamer8.modlauncher.host.modrinth;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;


@JsonIgnoreProperties(ignoreUnknown = true)
public record SearchHit(
        String project_id,
        String slug,
        String title,
        String description,
        List<String> versions,
        List<String> categories,
        String icon_url,
        String author,
        long downloads
) {}