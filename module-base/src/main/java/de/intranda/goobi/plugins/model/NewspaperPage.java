/**
 * This file is part of the Goobi Application - a Workflow tool for the support of mass digitization.
 * 
 * Visit the websites for more information.
 *          - https://goobi.io
 *          - https://www.intranda.com
 *          - https://github.com/intranda/goobi-workflow
 * 
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with this program; if not, write to the Free Software Foundation, Inc., 59
 * Temple Place, Suite 330, Boston, MA 02111-1307 USA
 * 
 * Linking this library statically or dynamically with other modules is making a combined work based on this library. Thus, the terms and conditions
 * of the GNU General Public License cover the whole combination. As a special exception, the copyright holders of this library give you permission to
 * link this library with independent modules to produce an executable, regardless of the license terms of these independent modules, and to copy and
 * distribute the resulting executable under terms of your choice, provided that you also meet, for each linked independent module, the terms and
 * conditions of the license of that module. An independent module is a module which is not derived from or based on this library. If you modify this
 * library, you may extend this exception to your version of the library, but you are not obliged to do so. If you do not wish to do so, delete this
 * exception statement from your version.
 */

package de.intranda.goobi.plugins.model;

import java.nio.file.Path;
import java.text.DateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import lombok.Getter;

/**
 * Represents a page of a newspaper in the digitization workflow. Provides methods for extracting and formatting date information from the file name,
 * checking the validity of the file, and obtaining a formatted European-style date.
 */
@Getter
public class NewspaperPage {

    private static final Pattern YEAR_PATTERN = Pattern.compile("2\\d{3}");
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}[\\W_]+\\d{2}[\\W_]+\\d{2}");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d{3}");
    private static final DateTimeFormatter FORMATTER_ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter FORMATTER_LONG = DateTimeFormatter.ofPattern("dd. MMM yyyy");

    private Path filePath;
    private String fileName;
    private LocalDate localdate;
    private String date;
    private String year;
    private String month;
    private String day;
    private String pageNumber;

    /**
     * Constructs a NewspaperPage object with the given file path.
     *
     * @param filePath The path to the newspaper page file.
     */
    public NewspaperPage(Path filePath) {
        this.filePath = filePath;
        fileName = filePath.getFileName().toString();
        pageNumber = fileName.substring(11, 14);

        date = getDateFromFileName(fileName);
        localdate = LocalDate.parse(date);

        String[] dateParts = date.split("[\\W_]+");
        if (dateParts.length >= 3) {
            year = dateParts[0];
            month = dateParts[1];
            day = dateParts[2];
        }
    }

    /**
     * Extracts the date information from the given file name.
     *
     * @param fileName The file name from which to extract the date.
     * @return The extracted date or an empty string if not found.
     */
    public static String getDateFromFileName(String fileName) {
        Matcher matcher = DATE_PATTERN.matcher(fileName);
        return matcher.find() ? matcher.group() : "";
    }

    /**
     * Gets the fine-formatted date (dd. MMM yyyy) of the newspaper page.
     *
     * @return The formatted date.
     */
    public String getDateFine() {
        return localdate.format(FORMATTER_LONG);
    }

    /**
     * Checks if the file size is valid (greater than 0 and readable).
     *
     * @return True if the file size is valid, false otherwise.
     */
    public boolean isFileSizeValid() {
        return filePath.toFile().canRead() && filePath.toFile().length() > 0;
    }

    /**
     * Checks if the file is valid based on date, page number, and file size.
     *
     * @return True if the file is valid, false otherwise.
     */
    public boolean isFileValid() {
        boolean vdate = isDateValid();
        boolean vpage = isPageNumberValid();
        boolean vsize = isFileSizeValid();
        return vdate && vpage && vsize;
    }

    /**
     * Checks if the file is invalid based on date, page number, and file size.
     *
     * @return True if the file is invalid, false otherwise.
     */
    public boolean isFileInvalid() {
        return !isFileValid();
    }

    private boolean isDateValid() {
        return StringUtils.isNoneBlank(date, year, month, day);
    }

    private boolean isPageNumberValid() {
        return pageNumber.length() == 3 && NUMBER_PATTERN.matcher(pageNumber).find();
    }

    /**
     * Gets the European-style formatted date (dd.mm.yyyy) together with day information for a user friendly string
     *
     * @return The formatted date with day description.
     */
    public String getUserFriendlyTitle(String lang, String titlePrefix) {
        DateFormat df = DateFormat.getDateInstance(DateFormat.FULL, Locale.forLanguageTag(lang));
        Date mydate = Date.from(localdate.atStartOfDay(ZoneId.systemDefault()).toInstant());
        return titlePrefix.trim() + " " + df.format(mydate);
    }

}
