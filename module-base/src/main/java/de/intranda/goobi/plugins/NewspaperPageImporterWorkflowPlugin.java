package de.intranda.goobi.plugins;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.collections4.queue.CircularFifoQueue;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.lang3.StringUtils;
import org.goobi.beans.Process;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IPushPlugin;
import org.goobi.production.plugin.interfaces.IWorkflowPlugin;
import org.omnifaces.cdi.PushContext;

import de.intranda.goobi.plugins.model.ImportMetadata;
import de.intranda.goobi.plugins.model.NewspaperPage;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.BeanHelper;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.ScriptThreadWithoutHibernate;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.StorageProviderInterface;
import de.sub.goobi.helper.enums.StepStatus;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.ProcessManager;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.ContentFile;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.DocStructType;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Person;
import ugh.dl.Prefs;
import ugh.exceptions.IncompletePersonObjectException;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.TypeNotAllowedAsChildException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.WriteException;
import ugh.fileformats.mets.MetsMods;

@PluginImplementation
@Log4j2
public class NewspaperPageImporterWorkflowPlugin implements IWorkflowPlugin, IPushPlugin {

    private static final StorageProviderInterface storageProvider = StorageProvider.getInstance();

    private static final String NEWSPAPER_TYPE = "Newspaper";
    private static final String NEWSPAPER_VOLUME_TYPE = "NewspaperVolume";
    private static final String NEWSPAPER_ISSUE_TYPE = "NewspaperIssue";
    private static final String TITLE_DOC_MAIN_TYPE = "TitleDocMain";
    private static final String DATE = "DateIssued";

    private static final String CONTENT_FILE_LOCATION_PREFIX = "file://";

    // set of dates of the issues that are already added
    private static final Set<String> ISSUES_SET = new HashSet<>();

    @Getter
    private String title = "intranda_workflow_newspaper_pages_importer";
    private long lastPush = System.currentTimeMillis();

    // list of metadata that shall be added to the anchor file
    @Getter
    private transient List<ImportMetadata> anchorMetadataList;
    // list of metadata that shall be added to the volume part of the mets file
    @Getter
    private transient List<ImportMetadata> volumeMetadataList;

    private PushContext pusher;
    @Getter
    private boolean run = false;
    @Getter
    private int progress = -1;
    @Getter
    private int itemCurrent = 0;
    @Getter
    private int itemsTotal = 0;
    @Getter
    private int errors;
    @Getter
    private transient Queue<LogMessage> logQueue = new CircularFifoQueue<>(1000);
    // folder containing images to import
    private String importFolder;
    // name of the workflow template that shall be used
    private String workflow;
    // process title
    private String processtitle;
    // page number prefix
    private String pageNumberPrefix;
    // issue title prefix
    private String issueTitlePrefix;
    private String issueTitlePrefixMorning;
    private String issueTitlePrefixEvening;
    // identifiers to detect morning and evening issues
    private String morningIssueIdentifier;
    private String eveningIssueIdentifier;
    // language for ate for issueTitle
    private String languageForDateFormat;
    // true if the images should be deleted from the import folder once they are imported, false otherwise
    private boolean deleteFromSource;
    @Getter
    private List<String> sets;

    private Prefs prefs;
    private Fileformat fileformat;

    @Override
    public PluginType getType() {
        return PluginType.Workflow;
    }

    @Override
    public String getGui() {
        return "/uii/plugin_workflow_newspaper_pages_importer.xhtml";
    }

    /**
     * Constructor
     */
    public NewspaperPageImporterWorkflowPlugin() {
        log.info("Newspaper pages importer workflow plugin started");
        errors = 0;

        // read sets
        sets = new ArrayList<String>();
        List<HierarchicalConfiguration> configSets = ConfigPlugins.getPluginConfig(title).configurationsAt("set");
        for (HierarchicalConfiguration config : configSets) {
            sets.add(config.getString("[@title]", "-"));
        }
    }

    /**
     * private method to read main configuration file
     */
    private void readConfiguration(String set) {
        updateLog("Reading configuration for set");
        errors = 0;

        // find the correct configuration block
        List<HierarchicalConfiguration> configSets = ConfigPlugins.getPluginConfig(title).configurationsAt("set");
        for (HierarchicalConfiguration config : configSets) {

            // if the correct set was found read it in and start the export
            if (config.getString("[@title]", "-").equals(set)) {
                importFolder = config.getString("importFolder");
                workflow = config.getString("workflow");
                processtitle = config.getString("processtitle");
                issueTitlePrefix = config.getString("issueTitlePrefix");
                issueTitlePrefixMorning = config.getString("issueTitlePrefixMorning");
                issueTitlePrefixEvening = config.getString("issueTitlePrefixEvening");
                morningIssueIdentifier = config.getString("issueTitlePrefixMorning[@identifier]");
                eveningIssueIdentifier = config.getString("issueTitlePrefixEvening[@identifier]");

                pageNumberPrefix = config.getString("pageNumberPrefix");
                languageForDateFormat = config.getString("languageForDateFormat", "de");
                deleteFromSource = config.getBoolean("deleteFromSource", false);
                anchorMetadataList = new ArrayList<>();
                volumeMetadataList = new ArrayList<>();

                // metadata mappings to use
                List<HierarchicalConfiguration> mappings = config.configurationsAt("metadata");
                for (HierarchicalConfiguration mapping : mappings) {
                    String type = mapping.getString("[@type]", "");
                    String value = mapping.getString("[@value]", "");
                    String variable = mapping.getString("[@var]", "");
                    boolean isPerson = mapping.getBoolean("[@person]", false);
                    boolean isAnchor = mapping.getBoolean("[@anchor]", false);
                    boolean isVolume = mapping.getBoolean("[@volume]", false);
                    ImportMetadata md = new ImportMetadata(type, value, variable, isPerson);
                    if (isAnchor) {
                        anchorMetadataList.add(md);
                    }
                    if (isVolume) {
                        volumeMetadataList.add(md);
                    }
                }
            }
        }

        // write a log into the UI
        updateLog("Configuration successfully read");
    }

    /**
     * cancel a running import
     */
    public void cancel() {
        run = false;
    }

    /**
     * main method to start the actual import
     */
    public void startImport(String set) {
        readConfiguration(set);
        List<NewspaperPage> allPagesSorted = getSortedNewspaperPages(importFolder);
        boolean allPagesValid = validateNewspaperPages(allPagesSorted);
        if (!allPagesValid) {
            updateLog("Aborting ...");
            return;
        }

        // all files are valid, get ready for import
        progress = 0;
        BeanHelper bhelp = new BeanHelper();

        Map<String, List<NewspaperPage>> pagesGroupedByYear = getSortedNewspaperPagesGroupedByYears(allPagesSorted);

        // run the import in a separate thread to allow a dynamic progress bar
        run = true;

        Runnable runnable = () -> {

            // read input file
            try {
                updateLog("Run through all import files");

                itemsTotal = getNumberOfPages(pagesGroupedByYear);
                itemCurrent = 0;

                for (Map.Entry<String, List<NewspaperPage>> entry : pagesGroupedByYear.entrySet()) {
                    Thread.sleep(100);
                    if (!run) {
                        break;
                    }

                    String year = entry.getKey();
                    List<NewspaperPage> pages = entry.getValue();
                    NewspaperPage firstPage = pages.get(0);
                    // create a new process for this year
                    Process process = tryCreateAndSaveNewProcess(bhelp, processtitle + "_" + year, firstPage);

                    if (process == null) {
                        String message = "Failed to create a new process for year " + year;
                        reportError(message);
                        continue;
                    }

                    prefs = process.getRegelsatz().getPreferences();
                    fileformat = process.readMetadataFile();

                    Map<String, List<NewspaperPage>> pagesGroupedByDates = getSortedNewspaperPagesGroupedByDates(pages);
                    for (Map.Entry<String, List<NewspaperPage>> issueEntry : pagesGroupedByDates.entrySet()) {
                        if (!run) {
                            break;
                        }
                        String issueDate = issueEntry.getKey().substring(0, issueEntry.getKey().indexOf("_"));
                        List<NewspaperPage> issuePages = issueEntry.getValue();
                        boolean success = tryUpdateOldProcessForIssue(process, issuePages);
                        if (!success) {
                            errors++;
                            String message = "Failed to add issue for date " + issueDate;
                            reportError(message);
                        }

                        itemCurrent += issuePages.size();
                        progress = 100 * itemCurrent / itemsTotal;
                        updateLog("Processed issue: " + issueDate);
                    }

                    // write changes into file
                    process.writeMetadataFile(fileformat);
                }

                // finally last push
                run = false;
                Thread.sleep(2000);
                updateLog("Import completed.");

            } catch (InterruptedException | ReadException | IOException | SwapException | WriteException | PreferencesException e) {
                errors++;
                Helper.setFehlerMeldung("Error while trying to execute the import: " + e.getMessage());
                log.error("Error while trying to execute the import", e);
                updateLog("Error while trying to execute the import: " + e.getMessage(), 3);
            }

        };
        new Thread(runnable).start();
    }

    /**
     * get all Newspapers ordered by date and type
     * 
     * @param folder
     * @return
     */
    private List<NewspaperPage> getSortedNewspaperPages(String folder) {
        return storageProvider.listFiles(folder)
                .stream()
                .map(fileName -> new NewspaperPage(fileName, morningIssueIdentifier, eveningIssueIdentifier))
                .sorted(byMultipleFields)
                .collect(Collectors.toList());
    }

    /**
     * Comparator for NewspaperPages to get them sorted by date and then by morning, regular and evening
     */
    Comparator<NewspaperPage> byMultipleFields = Comparator
            .comparing(NewspaperPage::getDate)
            .thenComparing(page -> {
                if (page.isMorningIssue()) {
                    return 1; // morning issues
                }
                if (page.isEveningIssue()) {
                    return 3; // evening issues
                }
                return 2; // general issues
            });

    private boolean validateNewspaperPages(List<NewspaperPage> pages) {
        boolean result = true;
        for (NewspaperPage p : pages) {
            if (!p.isDateValid()) {
                reportError("Date is invalid for file: " + p.getFilePath());
                result = false;
            }
            if (!p.isPageNumberValid()) {
                reportError("Page number is invalid for file: " + p.getFilePath());
                result = false;
            }
            if (!p.isFileSizeValid()) {
                reportError("File size is invalid for file: " + p.getFilePath());
                result = false;
            }
        }
        return result;
    }

    private Map<String, List<NewspaperPage>> getSortedNewspaperPagesGroupedByYears(List<NewspaperPage> pages) {
        return pages
                .stream()
                .collect(Collectors.groupingBy(NewspaperPage::getYear));
    }

    private int getNumberOfPages(Map<String, List<NewspaperPage>> pagesGrouped) {
        final int[] numberOfPages = { 0 };
        pagesGrouped.forEach((k, v) -> numberOfPages[0] += v.size());
        log.debug("numberOfPages = " + numberOfPages[0]);
        return numberOfPages[0];
    }

    private Map<String, List<NewspaperPage>> getSortedNewspaperPagesGroupedByDates(List<NewspaperPage> pages) {
        return pages
                .stream()
                .collect(Collectors.groupingBy(NewspaperPage::getDateAndType, LinkedHashMap::new, Collectors.toList()));
    }

    /**
     * try to add all pages of one issue to an old process by updating it
     * 
     * @param process Goobi process that shall be updated
     * @param pages list of NewspaperPages that belong to one issue
     * @return true if the input issue pages are successfully added into the old process, false otherwise
     */
    private boolean tryUpdateOldProcessForIssue(Process process, List<NewspaperPage> pages) {
        log.debug("Updating process: " + process.getTitel());
        try {
            updateMetadataOfProcessForIssue(process, pages);

        } catch (ReadException | IOException | SwapException e1) {
            // read Fileformat error
            errors++;
            String message = "Failed to read the fileformat.";
            reportError(message);
            e1.printStackTrace();
            return false;

        } catch (PreferencesException e) {
            // DigitalDocument error
            errors++;
            String message = "Failed to get the digital document.";
            reportError(message);
            e.printStackTrace();
            return false;

        } catch (Exception e) {
            errors++;
            log.debug("Unknown exception caught while updating process: " + process.getTitel());
            e.printStackTrace();
            return false;
        }

        // copy files into the master folder of the process
        try {
            copyPagesToMasterFolder(process, pages);
            return true;

        } catch (IOException | SwapException | DAOException e) {
            errors++;
            log.error("Error while trying to copy files into the media folder", e);
            String message = "Error while trying to copy files into the media folder: " + e.getMessage();
            reportError(message);
            return false;
        }
    }

    /**
     * update the metadata of the input process with metadata of the input list of NewspaperPages that belong to one issue
     * 
     * @param process Goobi process whose metadata shall be updated
     * @param page NewspaperPage belonging to one issue whose metadata shall be added into the process
     * @throws ReadException
     * @throws IOException
     * @throws SwapException
     * @throws PreferencesException
     */
    private void updateMetadataOfProcessForIssue(Process process, List<NewspaperPage> pages)
            throws ReadException, IOException, SwapException, PreferencesException {
        log.debug("Updating metadata of process: " + process.getTitel());
        try {

            // update metadata
            DigitalDocument dd = fileformat.getDigitalDocument();
            DocStruct logical = dd.getLogicalDocStruct();
            DocStruct volume = logical.getAllChildren().get(0);

            DocStruct issue = createNewIssue(prefs, dd, pages.get(0));
            if (issue != null) {
                volume.addChild(issue);
            }

            // add all pages to this issue
            for (NewspaperPage page : pages) {
                addPageToIssue(prefs, dd, issue, page);
            }

        } catch (Exception e) {
            errors++;
            log.debug("Exception caught while updating metadata of process: " + process.getTitel());
            e.printStackTrace();
        }

    }

    /**
     * try to create and save a new process
     * 
     * @param bhelp BeanHelper
     * @param processName title of the new process
     * @return the new process if it is successfully created and saved, otherwise null
     */
    private Process tryCreateAndSaveNewProcess(BeanHelper bhelp, String processName, NewspaperPage page) {
        // get the correct workflow to use
        Process template = ProcessManager.getProcessByExactTitle(workflow);
        // prepare the Fileformat based on the template Process
        Fileformat fileformat = prepareFileformatForNewProcess(template, page);
        if (fileformat == null) {
            // error happened during the preparation
            return null;
        }

        // save the process
        Process process = createAndSaveNewProcess(bhelp, template, processName, fileformat);
        if (process == null) {
            // error heppened while saving
            return null;
        }

        // TODO: find a proper way to start open automatic tasks only after all issues belonging to this process are added
        // start open automatic tasks
        //        startOpenAutomaticTasks(process); // NOSONAR

        updateLog("Process successfully created with ID: " + process.getId());

        return process;
    }

    /**
     * prepare the Fileformat for creating the new process
     * 
     * @param template Process template
     * @param page NewspaperPage
     * @return Fileformat
     */
    private Fileformat prepareFileformatForNewProcess(Process template, NewspaperPage page) {
        Prefs prefs = template.getRegelsatz().getPreferences();

        try {
            Fileformat fileformat = new MetsMods(prefs);
            DigitalDocument dd = new DigitalDocument();
            fileformat.setDigitalDocument(dd);

            // add the physical basics
            DocStruct physical = dd.createDocStruct(prefs.getDocStrctTypeByName("BoundBook"));
            dd.setPhysicalDocStruct(physical);
            Metadata mdForPath = new Metadata(prefs.getMetadataTypeByName("pathimagefiles"));
            mdForPath.setValue("file:///");
            physical.addMetadata(mdForPath);

            // add the logical basics to anchor
            DocStruct logical = dd.createDocStruct(prefs.getDocStrctTypeByName(NEWSPAPER_TYPE));
            dd.setLogicalDocStruct(logical);
            List<ImportMetadata> anchorMetadataListFinal = getMetadataListWithVariablesReplaced(this.anchorMetadataList, page);
            createMetadataFields(prefs, logical, anchorMetadataListFinal);

            // prepare the volume
            DocStruct volume = dd.createDocStruct(prefs.getDocStrctTypeByName(NEWSPAPER_VOLUME_TYPE));
            List<ImportMetadata> volumeMetadataListFinal = getMetadataListWithVariablesReplaced(this.volumeMetadataList, page);
            createMetadataFields(prefs, volume, volumeMetadataListFinal);

            log.debug("Adding DocStruct child: " + NEWSPAPER_VOLUME_TYPE);
            try {
                logical.addChild(volume);

            } catch (TypeNotAllowedAsChildException e) {
                errors++;
                String message = "Failed to add volume.";
                reportError(message);
                e.printStackTrace();
                return null;
            }

            return fileformat;

        } catch (PreferencesException | TypeNotAllowedForParentException | MetadataTypeNotAllowedException | IncompletePersonObjectException e) {
            errors++;
            String message = "Error while preparing the Fileformat for the new process: " + e.getMessage();
            reportError(message);
            return null;
        }

    }

    /**
     * get a list of ImportMetadata whose variables are all replaced
     * 
     * @param page NewspaperPage
     * @return a list of ImportMetadata whose variables are all replaced
     */
    private List<ImportMetadata> getMetadataListWithVariablesReplaced(List<ImportMetadata> metadataList, NewspaperPage page) {
        // Remark: NewspaperVolume should have a CatalogIDDigital that is different from the one of Newspaper

        List<ImportMetadata> metadataListFinal = new ArrayList<>();

        for (ImportMetadata md : metadataList) {
            // replace variables if configured and used
            ImportMetadata mdToAdd = getImportMetadataWithVariableReplaced(md, page);
            metadataListFinal.add(mdToAdd);
        }

        return metadataListFinal;
    }

    /**
     * get an ImportMetadata object where the occurrences of its predefined variable in its predefined value are all replaced properly
     * 
     * @param md ImportMetadata
     * @param page NewspaperPage
     * @return the input ImportMetadata itself if no such replacement is actually needed, otherwise a new one whose value is the original one with its
     *         variable properly replaced
     */
    private ImportMetadata getImportMetadataWithVariableReplaced(ImportMetadata md, NewspaperPage page) {
        String variable = md.getVariable();
        if (StringUtils.isBlank(variable)) {
            // no variable configured
            return md;
        }

        String variableWrapped = getStringWrapped(variable, "_");
        String metadataValue = md.getValue();
        if (!metadataValue.contains(variableWrapped)) {
            // no such variable in use, no replacement needed
            log.debug("Metadata value '" + metadataValue + " does not contain variableWrapped '" + variableWrapped + "'");
            return md;
        }

        String variableValue = getVariableValue(variable, page);
        String newMetadataValue = metadataValue.replace(variableWrapped, variableValue);
        return new ImportMetadata(md.getType(), newMetadataValue, "", md.isPerson());
    }

    /**
     * get a string wrapped with the same wrapper from both sides
     * 
     * @param s the string that shall be wrapped
     * @param wrapper wrapper string that shall be added to both sides
     * @return the string wrapped in the wrapper from both sides
     */
    private String getStringWrapped(String s, String wrapper) {
        return getStringWrapped(s, wrapper, wrapper);
    }

    /**
     * get a string wrapped with possibly different wrapper strings from both sides
     * 
     * @param s the string that shall be wrapped
     * @param wrapperLeft wrapper string that shall be added to the left
     * @param wrapperRight wrapper string that shall be added to the right
     * @return the string wrapped with the input two wrappers
     */
    private String getStringWrapped(String s, String wrapperLeft, String wrapperRight) {
        return wrapperLeft + s + wrapperRight;
    }

    /**
     * get the value of the variable from the input NewspaperPage
     * 
     * @param variable name of the variable
     * @param page NewspaperPage from which the value is to be fetched
     * @return the value of the variable if it is recognized, or the variable itself otherwise
     */
    private String getVariableValue(String variable, NewspaperPage page) {
        switch (variable.toLowerCase()) {
            case "year":
                return page.getYear();
            case "month":
                return page.getMonth();
            case "day":
                return page.getDay();
            case "date":
                return page.getDate();
            case "datefine":
                return page.getDateFine();
            case "page":
                return page.getPageNumber();
            default:
                // unknown variable
                return variable;
        }
    }

    /**
     * create all metadata fields and add them to the input DocStruct
     * 
     * @param prefs Prefs
     * @param ds DocStruct
     * @param importMetadataList list of ImportMetadata
     */
    private void createMetadataFields(Prefs prefs, DocStruct ds, List<ImportMetadata> importMetadataList) {
        for (ImportMetadata importMetadata : importMetadataList) {
            // prepare the MetadataType
            String target = importMetadata.getType();
            MetadataType targetType = prefs.getMetadataTypeByName(target);
            String value = importMetadata.getValue();

            boolean isPerson = importMetadata.isPerson();

            try {
                Metadata md = createMetadata(targetType, value, isPerson);
                if (isPerson) {
                    updateLog("Add person '" + target + "' with value '" + value + "'");
                    ds.addPerson((Person) md);
                } else {
                    updateLog("Add metadata '" + target + "' with value '" + value + "'");
                    log.debug("ds.type = " + ds.getType());
                    ds.addMetadata(md);
                }
            } catch (MetadataTypeNotAllowedException e) {
                errors++;
                String message = "MetadataType " + target + " is not allowed. Skipping ...";
                reportError(message);
                e.printStackTrace();
            }
        }
    }

    /**
     * create Metadata
     * 
     * @param targetType MetadataType
     * @param value value of the new Metadata
     * @param isPerson
     * @return the new Metadata object created
     * @throws MetadataTypeNotAllowedException
     */
    private Metadata createMetadata(MetadataType targetType, String value, boolean isPerson) throws MetadataTypeNotAllowedException {
        // treat persons different than regular metadata
        if (isPerson) {
            Person p = new Person(targetType);
            int splitIndex = value.indexOf(" ");
            String firstName = value.substring(0, splitIndex);
            String lastName = value.substring(splitIndex);
            p.setFirstname(firstName);
            p.setLastname(lastName);

            return p;
        }

        Metadata md = new Metadata(targetType);
        md.setValue(value);

        return md;
    }

    /**
     * create a new DocStruct of type NewspaperIssue
     * 
     * @param prefs Prefs
     * @param dd DigitalDocument
     * @param page NewspaperPage
     * @return the new DocStruct of type NewspaperIssue if it is successfully created, or null otherwise
     */
    private DocStruct createNewIssue(Prefs prefs, DigitalDocument dd, NewspaperPage page) {
        log.debug("Creating new issue from NewspaperPage: " + page.getFileName());

        try {
            DocStruct issue = dd.createDocStruct(prefs.getDocStrctTypeByName(NEWSPAPER_ISSUE_TYPE));

            // TitleDocMain
            MetadataType titleType = prefs.getMetadataTypeByName(TITLE_DOC_MAIN_TYPE);
            String titleValue = page.getUserFriendlyTitle(languageForDateFormat, issueTitlePrefix);
            if (page.isMorningIssue()) {
                titleValue = page.getUserFriendlyTitle(languageForDateFormat, issueTitlePrefixMorning);
            }
            if (page.isEveningIssue()) {
                titleValue = page.getUserFriendlyTitle(languageForDateFormat, issueTitlePrefixEvening);
            }

            Metadata titleMetadata = createMetadata(titleType, titleValue, false);
            issue.addMetadata(titleMetadata);

            // Date
            MetadataType currentNoType = prefs.getMetadataTypeByName(DATE);
            String currentNoValue = page.getDate();
            Metadata currentNoMetadata = createMetadata(currentNoType, currentNoValue, false);
            issue.addMetadata(currentNoMetadata);

            ISSUES_SET.add(titleValue);
            log.debug("New issue created: " + titleValue);

            return issue;

        } catch (TypeNotAllowedForParentException | MetadataTypeNotAllowedException e) {
            errors++;
            String message = "Failed to create a new issue for " + page.getDate();
            reportError(message);
            e.printStackTrace();
            return null;

        }
    }

    /**
     * add a NewspaperPage to an issue
     * 
     * @param prefs Prefs
     * @param dd DigitalDocument
     * @param issue DocStruct of type NewspaperIssue
     * @param page NewspaperPage that shall be added to the input issue
     */
    private void addPageToIssue(Prefs prefs, DigitalDocument dd, DocStruct issue, NewspaperPage page) {
        log.debug("Adding new page '" + page.getPageNumber() + "' to issue '" + page.getDate());
        DocStruct physical = dd.getPhysicalDocStruct();
        DocStruct volume = dd.getLogicalDocStruct().getAllChildren().get(0);
        DocStructType pageType = prefs.getDocStrctTypeByName("page");
        String pageLogNumber = pageNumberPrefix.trim() + " " + Integer.valueOf(page.getPageNumber());

        try {
            DocStruct dsPage = dd.createDocStruct(pageType);
            physical.addChild(dsPage);

            Metadata metaPhysPageNumber = new Metadata(prefs.getMetadataTypeByName("physPageNumber"));
            metaPhysPageNumber.setValue(String.valueOf(physical.getAllChildren().size()));
            dsPage.addMetadata(metaPhysPageNumber);

            Metadata metaLogPageNumber = new Metadata(prefs.getMetadataTypeByName("logicalPageNumber"));
            metaLogPageNumber.setValue(pageLogNumber.trim());
            dsPage.addMetadata(metaLogPageNumber);

            volume.addReferenceTo(dsPage, "logical_physical");
            issue.addReferenceTo(dsPage, "logical_physical");

            ContentFile contentFileTiff = prepareContentFileForPage(page, "tiff");
            dsPage.addContentFile(contentFileTiff);

        } catch (TypeNotAllowedForParentException | TypeNotAllowedAsChildException | MetadataTypeNotAllowedException e) {
            errors++;
            String message = "Failed to add page '" + page.getFileName() + "' to issue.";
            reportError(message);
            e.printStackTrace();

        }
    }

    /**
     * prepare the ContentFile for the input NewspaperPage
     * 
     * @param page NwespaperPage
     * @param type type of the page file
     * @return the ContentFile for the input NewspaperPage
     */
    private ContentFile prepareContentFileForPage(NewspaperPage page, String type) {
        ContentFile cf = new ContentFile();
        String pageName = page.getFileName();

        String mimeType = "";
        String locationSuffix = "";
        switch (type.toLowerCase()) {
            case "jpg":
            case "jpeg":
                mimeType = "image/jpeg";
                locationSuffix = replaceFileExtension(pageName, "jpg");
                break;
            case "tif":
            case "tiff":
                mimeType = "image/tiff";
                locationSuffix = replaceFileExtension(pageName, "tiff");
                break;
            case "pdf":
                mimeType = "application/pdf";
                locationSuffix = replaceFileExtension(pageName, "pdf");
                break;
            default:
                // no need here since this is just a private method
        }
        cf.setMimetype(mimeType);
        cf.setLocation(CONTENT_FILE_LOCATION_PREFIX + locationSuffix);

        return cf;
    }

    /**
     * replace the file extension of the input fileName with the input new extension
     * 
     * @param fileName name of the file whose extension is to be replaced
     * @param extension new extension
     * @return the file name with its extension replaced by the new one
     */
    private String replaceFileExtension(String fileName, String extension) {
        int extensionIndex = fileName.lastIndexOf(".");
        return fileName.substring(0, extensionIndex) + "." + extension;
    }

    /**
     * create and save a new Goobi process
     * 
     * @param bhelp BeanHelper
     * @param template Goobi process template that is to be used
     * @param processName name of the new process
     * @param fileformat Fileformat
     * @return the new Goobi process if it is successfully created and saved, otherwise null
     */
    private Process createAndSaveNewProcess(BeanHelper bhelp, Process template, String processName, Fileformat fileformat) {
        // save the process
        Process process = bhelp.createAndSaveNewProcess(template, processName, fileformat);

        try {
            ProcessManager.saveProcess(process);
        } catch (DAOException e) {
            errors++;
            String message = "Error while trying to save the process: " + e.getMessage();
            reportError(message);
            return null;
        }

        return process;
    }

    /**
     * COPY (or MOVE if <deleteFromSource> is configured true) the images from importFolder to master folders of the process
     * 
     * @param process Process whose master folder is targeted
     * @param filePath path of the file
     * @throws IOException
     * @throws SwapException
     * @throws DAOException
     */
    private void copyFileToMasterFolder(Process process, Path filePath) throws IOException, SwapException, DAOException {
        // prepare the directories
        String masterBase = process.getImagesOrigDirectory(false);
        storageProvider.createDirectories(Path.of(masterBase));
        File file = filePath.toFile();
        if (file.canRead()) {
            String fileName = filePath.getFileName().toString();
            Path targetPath = Path.of(masterBase, fileName);

            if (deleteFromSource) {
                storageProvider.move(filePath, targetPath);
            } else {
                storageProvider.copyFile(filePath, targetPath);
            }
        }
    }

    private void copyPagesToMasterFolder(Process process, List<NewspaperPage> pages) throws IOException, SwapException, DAOException {
        for (NewspaperPage page : pages) {
            Path path = page.getFilePath();
            copyFileToMasterFolder(process, path);
        }
    }

    /**
     * start all automatic tasks that are open
     * 
     * @param process
     */
    private void startOpenAutomaticTasks(Process process) { // NOSONAR
        // start any open automatic tasks for the created process
        for (Step s : process.getSchritteList()) {
            if (StepStatus.OPEN.equals(s.getBearbeitungsstatusEnum()) && s.isTypAutomatisch()) {
                ScriptThreadWithoutHibernate myThread = new ScriptThreadWithoutHibernate(s);
                myThread.startOrPutToQueue();
            }
        }
    }

    /**
     * report error
     * 
     * @param message error message
     */
    private void reportError(String message) {
        log.error(message);
        updateLog(message, 3);
        Helper.setFehlerMeldung(message);
        pusher.send("error");
    }

    @Override
    public void setPushContext(PushContext pusher) {
        this.pusher = pusher;
    }

    /**
     * simple method to send status message to gui
     *
     * @param logmessage
     */
    private void updateLog(String logmessage) {
        updateLog(logmessage, 0);
    }

    /**
     * simple method to send status message with specific level to gui
     *
     * @param logmessage
     */
    private void updateLog(String logmessage, int level) {
        logQueue.add(new LogMessage(logmessage, level));
        log.debug(logmessage);
        if (pusher != null && System.currentTimeMillis() - lastPush > 500) {
            lastPush = System.currentTimeMillis();
            pusher.send("update");
        }
    }

    @Data
    @AllArgsConstructor
    public class LogMessage {
        private String message;
        private int level = 0;
    }
}
