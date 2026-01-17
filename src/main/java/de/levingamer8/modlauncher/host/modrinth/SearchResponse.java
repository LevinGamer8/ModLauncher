package de.levingamer8.modlauncher.host.modrinth;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;


@JsonIgnoreProperties(ignoreUnknown = true)
public record SearchResponse(
        List<SearchHit> hits,
        int offset,
        int limit,
        int total_hits
) {}