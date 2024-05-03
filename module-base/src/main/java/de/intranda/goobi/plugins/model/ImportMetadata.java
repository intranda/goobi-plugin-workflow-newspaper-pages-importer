package de.intranda.goobi.plugins.model;

import lombok.Getter;

public class ImportMetadata {
    private static final String CATALOG_ID_DIGITAL_TYPE = "CatalogIDDigital";
    private static final String CALALOG_ID_SOURCE_TYPE = "CatalogIDSource";

    @Getter
    private String type;
    @Getter
    private String value;
    @Getter
    private String variable;
    @Getter
    private boolean person;

    public ImportMetadata(String type, String value, String variable, boolean person) {
        this.type = type;
        this.value = removeSpaceIfNeeded(value, type);
        this.variable = variable;
        this.person = person;
    }

    private static String removeSpaceIfNeeded(String value, String type) {
        return isSpaceAllowedForType(type) ? value : value.replace(" ", "");
    }

    private static boolean isSpaceAllowedForType(String type) {
        return !CATALOG_ID_DIGITAL_TYPE.equals(type) && !CALALOG_ID_SOURCE_TYPE.equals(type);
    }
}