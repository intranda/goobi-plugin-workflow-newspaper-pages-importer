package de.intranda.goobi.plugins.model;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.Getter;

@Getter
public class NewspaperPage {
    private static final Pattern YEAR_PATTERN = Pattern.compile("2\\d{3}");
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private Path filePath;
    private String fileName;

    private String date;
    private String year;
    private String month;
    private String day;
    private String pageNumber;

    public NewspaperPage(Path filePath) {
        this.filePath = filePath;
        fileName = filePath.getFileName().toString();
        date = getDateFromFileName(fileName);
        String[] dateParts = date.split("-");
        year = dateParts[0];
        month = dateParts[1];
        day = dateParts[2];
        pageNumber = fileName.substring(0, 3);
    }

    public static String getDateFromFileName(String fileName) {
        Matcher matcher = DATE_PATTERN.matcher(fileName);
        return matcher.find() ? matcher.group() : "";
    }

}
