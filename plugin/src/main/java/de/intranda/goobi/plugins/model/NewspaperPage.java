package de.intranda.goobi.plugins.model;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import lombok.Getter;

@Getter
public class NewspaperPage {
    private static final Pattern YEAR_PATTERN = Pattern.compile("2\\d{3}");
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}[\\W_]+\\d{2}[\\W_]+\\d{2}");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d{3}");
    private static final DateTimeFormatter DATE_TIME_PARSER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd. MMM yyyy");

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
        pageNumber = fileName.substring(0, Math.min(3, fileName.length()));

        date = getDateFromFileName(fileName);
        String[] dateParts = date.split("[\\W_]+");
        if (dateParts.length >= 3) {
            year = dateParts[0];
            month = dateParts[1];
            day = dateParts[2];
        }
    }

    public String getDateEuropean() {
        return day + "." + month + "." + year;
    }

    public static String getDateFromFileName(String fileName) {
        Matcher matcher = DATE_PATTERN.matcher(fileName);
        return matcher.find() ? matcher.group() : "";
    }

    public String getDateFine() {
        return LocalDate.parse(date, DATE_TIME_PARSER).format(DATE_TIME_FORMATTER);
    }

    public boolean isFileSizeValid() {
        return filePath.toFile().canRead() && filePath.toFile().length() > 0;
    }

    public boolean isFileValid() {
        return isDateValid() && isPageNumberValid() && isFileSizeValid();
    }

    public boolean isFileInvalid() {
        return !isFileValid();
    }

    private boolean isDateValid() {
        return StringUtils.isNoneBlank(date, year, month, day);
    }

    private boolean isPageNumberValid() {
        return pageNumber.length() == 3 && NUMBER_PATTERN.matcher(pageNumber).find();
    }

}
