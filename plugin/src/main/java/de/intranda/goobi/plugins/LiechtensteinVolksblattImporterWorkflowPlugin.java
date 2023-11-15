package de.intranda.goobi.plugins;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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
import ugh.fileformats.mets.MetsMods;

@PluginImplementation
@Log4j2
public class LiechtensteinVolksblattImporterWorkflowPlugin implements IWorkflowPlugin, IPushPlugin {

    private static final StorageProviderInterface storageProvider = StorageProvider.getInstance();

    private static final String NEWSPAPER_TYPE = "Newspaper";
    private static final String NEWSPAPER_VOLUME_TYPE = "NewspaperVolume";
    private static final String NEWSPAPER_ISSUE_TYPE = "NewspaperIssue";

    private static final String TITLE_DOC_MAIN_TYPE = "TitleDocMain";

    private static final String PART_NUMBER_TYPE = "PartNumber";

    private static final String CONTENT_FILE_LOCATION_PREFIX = "file://";

    // set of dates of the issues that are already added
    private static final Set<String> ISSUES_SET = new HashSet<>();

    @Getter
    private String title = "intranda_workflow_liechtenstein_volksblatt_importer";
    private long lastPush = System.currentTimeMillis();

    @Getter
    private List<ImportMetadata> anchorMetadataList;
    @Getter
    private List<ImportMetadata> volumeMetadataList;

    private PushContext pusher;
    @Getter
    private boolean run = false;
    @Getter
    private int progress = -1;
    @Getter
    private int itemCurrent = 0;
    @Getter
    int itemsTotal = 0;
    @Getter
    private Queue<LogMessage> logQueue = new CircularFifoQueue<>(48);
    private String importFolder;
    private String workflow;

    private static final Comparator<NewspaperPage> byIssueDate = (NewspaperPage page1, NewspaperPage page2) -> {
        String date1 = page1.getDate();
        String date2 = page2.getDate();
        return date1.compareTo(date2);
    };

    @Override
    public PluginType getType() {
        return PluginType.Workflow;
    }

    @Override
    public String getGui() {
        return "/uii/plugin_workflow_liechtenstein_volksblatt_importer.xhtml";
    }

    /**
     * Constructor
     */
    public LiechtensteinVolksblattImporterWorkflowPlugin() {
        log.info("Sample importer workflow plugin started");

        // read important configuration first
        readConfiguration();
    }

    /**
     * private method to read main configuration file
     */
    private void readConfiguration() {
        updateLog("Start reading the configuration");

        // read some main configuration
        importFolder = ConfigPlugins.getPluginConfig(title).getString("importFolder");
        workflow = ConfigPlugins.getPluginConfig(title).getString("workflow");

        anchorMetadataList = new ArrayList<>();
        volumeMetadataList = new ArrayList<>();
        List<HierarchicalConfiguration> mappings = ConfigPlugins.getPluginConfig(title).configurationsAt("metadata");
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
    public void startImport() {
        progress = 0;
        BeanHelper bhelp = new BeanHelper();

        // run the import in a separate thread to allow a dynamic progress bar
        run = true;
        Runnable runnable = () -> {

            // read input file
            try {
                updateLog("Run through all import files");
                int start = 0;

                List<NewspaperPage> pages = getSortedNewspaperPages(importFolder);
                int end = pages.size();

                itemsTotal = end - start;
                itemCurrent = start;

                // run through import files (e.g. from importFolder)
                for (NewspaperPage page : pages) {
                    Thread.sleep(100);
                    if (!run) {
                        break;
                    }

                    boolean success = addPdfFileToProcess(bhelp, page);
                    if (!success) {
                        String message = "Error while creating a process during the import";
                        reportError(message);
                    }

                    // recalculate progress
                    itemCurrent++;
                    progress = 100 * itemCurrent / itemsTotal;
                    updateLog("Processing of record done.");
                }

                // finally last push
                run = false;
                Thread.sleep(2000);
                updateLog("Import completed.");
            } catch (InterruptedException e) {
                Helper.setFehlerMeldung("Error while trying to execute the import: " + e.getMessage());
                log.error("Error while trying to execute the import", e);
                updateLog("Error while trying to execute the import: " + e.getMessage(), 3);
            }

        };
        new Thread(runnable).start();
    }

    private List<NewspaperPage> getSortedNewspaperPages(String folder) {
        return storageProvider.listFiles(folder)
                .stream()
                .map(NewspaperPage::new)
                .sorted(byIssueDate)
                .collect(Collectors.toList());
    }

    private boolean addPdfFileToProcess(BeanHelper bhelp, NewspaperPage page) {
        String processName = page.getYear();
        updateLog("Start importing: " + processName, 1);

        // check existen of process
        Process existingProcess = getProcessByName(processName);
        boolean processExists = existingProcess != null;

        return processExists ? tryUpdateOldProcess(existingProcess, page) : tryCreateAndSaveNewProcess(bhelp, processName, page);
    }

    private Process getProcessByName(String processName) {
        log.debug("Trying to retrieve the process if it exists.");
        // null will be returned if no such process exists
        return ProcessManager.getProcessByTitle(processName);
    }

    private boolean tryUpdateOldProcess(Process process, NewspaperPage page) {
        log.debug("Updating process: " + process.getTitel());
        Path pdfFilePath = page.getFilePath();
        try {
            updateMetadataOfProcess(process, page);

        } catch (ReadException | IOException | SwapException e1) {
            // read Fileformat error
            String message = "Failed to read the fileformat.";
            reportError(message);
            e1.printStackTrace();

        } catch (PreferencesException e) {
            // DigitalDocument error
            String message = "Failed to get the digital document.";
            reportError(message);
            e.printStackTrace();

        } catch (Exception e) {
            log.debug("Unknown exception caught while updating process: " + process.getTitel());
            e.printStackTrace();
        }

        // copy files into the media folder of the process
        try {
            copyFileToMasterFolder(process, pdfFilePath);
        } catch (IOException | SwapException | DAOException e) {
            String message = "Error while trying to copy files into the media folder: " + e.getMessage();
            reportError(message);
            return false;
        }

        return true;
    }

    private void updateMetadataOfProcess(Process process, NewspaperPage page) throws ReadException, IOException, SwapException, PreferencesException {
        log.debug("updating metadata of process: " + process.getTitel());
        try {
            Prefs prefs = process.getRegelsatz().getPreferences();
            // read metadata
            Fileformat fileformat = process.readMetadataFile();

            log.debug("fileformat is " + (fileformat == null ? "" : "NOT") + " null");

            // update metadata
            DigitalDocument dd = fileformat.getDigitalDocument();
            DocStruct logical = dd.getLogicalDocStruct();
            DocStruct volume = logical.getAllChildren().get(0);

            DocStruct issue = getIssueForPage(prefs, dd, volume, page);
            if (issue == null) {
                // error happened
                return;
            }

            // add page to issue
            addPageToIssue(prefs, dd, issue, page);

            // write changes into file
            process.writeMetadataFile(fileformat);

        } catch (Exception e) {
            log.debug("Exception caught while updating metadata of process: " + process.getTitel());
            e.printStackTrace();
        }

    }

    private DocStruct getIssueForPage(Prefs prefs, DigitalDocument dd, DocStruct volume, NewspaperPage page) throws TypeNotAllowedAsChildException {
        String pageDateEuropean = page.getDateEuropean();

        if (!ISSUES_SET.contains(pageDateEuropean)) {
            // issue does not exist yet, create a new one
            DocStruct issue = createNewIssue(prefs, dd, page);
            if (issue != null) {
                volume.addChild(issue);
            }
            return issue;
        }

        // issue already exists, go find it
        List<DocStruct> newspaperIssues = dd.getAllDocStructsByType(NEWSPAPER_ISSUE_TYPE);
        MetadataType titleType = prefs.getMetadataTypeByName(TITLE_DOC_MAIN_TYPE);
        for (DocStruct issue : newspaperIssues) {
            // TODO: the following logic must be optimized for a large amount of issues
            String issueTitle = issue.getAllMetadataByType(titleType).get(0).getValue();

            if (pageDateEuropean.equals(issueTitle)) {
                return issue;
            }
        }

        return null;
    }

    /**
     * try to create and save a new process
     * 
     * @param bhelp BeanHelper
     * @param processName title of the new process
     * @return true if a new process is successfully created and saved, otherwise false
     */
    private boolean tryCreateAndSaveNewProcess(BeanHelper bhelp, String processName, NewspaperPage page) {
        // get the correct workflow to use
        Process template = ProcessManager.getProcessByExactTitle(workflow);
        // prepare the Fileformat based on the template Process
        Fileformat fileformat = prepareFileformatForNewProcess(template, processName, page);
        if (fileformat == null) {
            // error happened during the preparation
            return false;
        }

        // save the process
        Process process = createAndSaveNewProcess(bhelp, template, processName, fileformat);
        if (process == null) {
            // error heppened while saving
            return false;
        }

        // copy files into the media folder of the process
        try {
            copyFileToMasterFolder(process, page.getFilePath());
        } catch (IOException | SwapException | DAOException e) {
            String message = "Error while trying to copy files into the media folder: " + e.getMessage();
            reportError(message);
            return false;
        }

        // start open automatic tasks
        startOpenAutomaticTasks(process);

        updateLog("Process successfully created with ID: " + process.getId());

        return true;
    }

    /**
     * prepare the Fileformat for creating the new process
     * 
     * @param template Process template
     * @param processName title of the new process
     * @param page NewspaperPage
     * @return Fileformat
     */
    private Fileformat prepareFileformatForNewProcess(Process template, String processName, NewspaperPage page) {
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
            createMetadataFields(prefs, logical, anchorMetadataList);

            // prepare the volume
            DocStruct volume = dd.createDocStruct(prefs.getDocStrctTypeByName(NEWSPAPER_VOLUME_TYPE));
            List<ImportMetadata> volumeMetadataList = prepareVolumeMetadataList(page);
            createMetadataFields(prefs, volume, volumeMetadataList);

            log.debug("adding DocStruct child: " + NEWSPAPER_VOLUME_TYPE);
            try {
                logical.addChild(volume);

            } catch (TypeNotAllowedAsChildException e) {
                String message = "Failed to add volume.";
                reportError(message);
                e.printStackTrace();
                return null;
            }

            // prepare a new issue
            DocStruct issue = createNewIssue(prefs, dd, page);
            if (issue == null) {
                // error happened
                return null;
            }

            try {
                volume.addChild(issue);

            } catch (TypeNotAllowedAsChildException e) {
                String message = "Failed to add the issue '" + page.getDate() + "' to volume.";
                reportError(message);
                e.printStackTrace();
                return null;
            }

            // link page to issue
            addPageToIssue(prefs, dd, issue, page);

            return fileformat;

        } catch (PreferencesException | TypeNotAllowedForParentException | MetadataTypeNotAllowedException | IncompletePersonObjectException e) {
            String message = "Error while preparing the Fileformat for the new process: " + e.getMessage();
            reportError(message);
            return null;
        }

    }

    private List<ImportMetadata> prepareVolumeMetadataList(NewspaperPage page) {
        // NewspaperVolume should have a CatalogIDDigital that is different from the one of Newspaper

        List<ImportMetadata> volumeMetadataList = new ArrayList<>();

        for (ImportMetadata md : this.volumeMetadataList) {
            // replace variables if configured and used
            ImportMetadata mdToAdd = getImportMetadataWithVariableReplaced(md, page);
            volumeMetadataList.add(mdToAdd);
        }

        return volumeMetadataList;
    }

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
            log.debug("metadataValue '" + metadataValue + " does not contain variableWrapped '" + variableWrapped + "'");
            return md;
        }
        
        String variableValue = getVariableValue(variable, page);
        String newMetadataValue = metadataValue.replace(variableWrapped, variableValue);
        return new ImportMetadata(md.getType(), newMetadataValue, "", md.isPerson());
    }

    private String getStringWrapped(String s, String wrapper) {
        return getStringWrapped(s, wrapper, wrapper);
    }

    private String getStringWrapped(String s, String wrapperLeft, String wrapperRight) {
        return wrapperLeft + s + wrapperRight;
    }

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
            case "page":
                return page.getPageNumber();
            default:
                // unknown variable
                return variable;
        }
    }

    /**
     * create all metadata fields
     * 
     * @param prefs Prefs
     * @param ds DocStruct
     * @param ImportMetadataList list of ImportMetadata
     */
    private void createMetadataFields(Prefs prefs, DocStruct ds, List<ImportMetadata> ImportMetadataList) {
        for (ImportMetadata ImportMetadata : ImportMetadataList) {
            // prepare the MetadataType
            String target = ImportMetadata.getType();
            MetadataType targetType = prefs.getMetadataTypeByName(target);
            String value = ImportMetadata.getValue();

            boolean isPerson = ImportMetadata.isPerson();

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
                String message = "MetadataType " + target + " is not allowed. Skipping...";
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

    private DocStruct createNewIssue(Prefs prefs, DigitalDocument dd, NewspaperPage page) {
        log.debug("Creating new issue from NewspaperPage: " + page.getFileName());

        try {
            DocStruct issue = dd.createDocStruct(prefs.getDocStrctTypeByName(NEWSPAPER_ISSUE_TYPE));

            // TitleDocMain
            MetadataType titleType = prefs.getMetadataTypeByName(TITLE_DOC_MAIN_TYPE);
            String titleValue = page.getDateEuropean();
            Metadata titleMetadata = createMetadata(titleType, titleValue, false);
            issue.addMetadata(titleMetadata);

            // PartNumber
            MetadataType partNumberType = prefs.getMetadataTypeByName(PART_NUMBER_TYPE);
            String partNumberValue = page.getDate();
            Metadata partNumberMetadata = createMetadata(partNumberType, partNumberValue, false);
            issue.addMetadata(partNumberMetadata);

            ISSUES_SET.add(titleValue);
            log.debug("New issue created: " + titleValue);

            return issue;

        } catch (TypeNotAllowedForParentException | MetadataTypeNotAllowedException e) {
            String message = "Failed to create a new issue for " + page.getDate();
            reportError(message);
            e.printStackTrace();
            return null;

        }
    }

    private void addPageToIssue(Prefs prefs, DigitalDocument dd, DocStruct issue, NewspaperPage page) {
        log.debug("adding new page '" + page.getPageNumber() + "' to issue '" + page.getDate());
        DocStruct physical = dd.getPhysicalDocStruct();
        DocStruct volume = dd.getLogicalDocStruct().getAllChildren().get(0);
        DocStructType pageType = prefs.getDocStrctTypeByName("page");
        String pageLogNumber = "S." + String.valueOf(Integer.valueOf(page.getPageNumber()));

        try {
            DocStruct dsPage = dd.createDocStruct(pageType);
            physical.addChild(dsPage);

            Metadata metaPhysPageNumber = new Metadata(prefs.getMetadataTypeByName("physPageNumber"));
            metaPhysPageNumber.setValue(String.valueOf(physical.getAllChildren().size()));
            dsPage.addMetadata(metaPhysPageNumber);

            Metadata metaLogPageNumber = new Metadata(prefs.getMetadataTypeByName("logicalPageNumber"));
            metaLogPageNumber.setValue(pageLogNumber);
            dsPage.addMetadata(metaLogPageNumber);

            volume.addReferenceTo(dsPage, "logical_physical");
            issue.addReferenceTo(dsPage, "logical_physical");

            ContentFile contentFileTiff = prepareContentFileForPage(page, "tiff");
            dsPage.addContentFile(contentFileTiff);

            ContentFile contentFileJpeg = prepareContentFileForPage(page, "jpg");
            dsPage.addContentFile(contentFileJpeg);


        } catch (TypeNotAllowedForParentException | TypeNotAllowedAsChildException | MetadataTypeNotAllowedException e) {
            String message = "Failed to add page '" + page.getFileName() + "' to issue.";
            reportError(message);
            e.printStackTrace();

        }
    }

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

    private String replaceFileExtension(String fileName, String extension) {
        int extensionIndex = fileName.lastIndexOf(".");
        return fileName.substring(0, extensionIndex) + "." + extension;
    }

    private Process createAndSaveNewProcess(BeanHelper bhelp, Process template, String processName, Fileformat fileformat) {
        // save the process
        Process process = bhelp.createAndSaveNewProcess(template, processName, fileformat);

        // add some properties
        bhelp.EigenschaftHinzufuegen(process, "Template", template.getTitel());
        bhelp.EigenschaftHinzufuegen(process, "TemplateID", "" + template.getId());

        try {
            ProcessManager.saveProcess(process);

        } catch (DAOException e) {
            String message = "Error while trying to save the process: " + e.getMessage();
            reportError(message);
            return null;
        }

        return process;
    }

    /**
     * copy the images from importFolder to master folders of the process
     * 
     * @param process Process whose master folder is targeted
     * @throws IOException
     * @throws SwapException
     * @throws DAOException
     */
    private void copyFileToMasterFolder(Process process, Path pdfFilePath) throws IOException, SwapException, DAOException {
        // if media files are given, import these into the media folder of the process
        updateLog("Start copying pdf files to the master folder");
        // prepare the directories
        String masterBase = process.getImagesOrigDirectory(false);
        storageProvider.createDirectories(Path.of(masterBase));
        File file = pdfFilePath.toFile();
        if (file.canRead()) {
            String fileName = pdfFilePath.getFileName().toString();
            log.debug("fileName = " + fileName);
            Path targetPath = Path.of(masterBase, fileName);
            //            storageProvider.move(pdfFilePath, targetPath); // Should we make this configurable?
            storageProvider.copyFile(pdfFilePath, targetPath); // for the ease of testing
        }
    }

    /**
     * start all automatic tasks that are open
     * 
     * @param process
     */
    private void startOpenAutomaticTasks(Process process) {
        // start any open automatic tasks for the created process
        for (Step s : process.getSchritteList()) {
            if (s.getBearbeitungsstatusEnum().equals(StepStatus.OPEN) && s.isTypAutomatisch()) {
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
     * @param logmessage
     */
    private void updateLog(String logmessage) {
        updateLog(logmessage, 0);
    }

    /**
     * simple method to send status message with specific level to gui
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
    public class ImportMetadata {
        private String type;
        private String value;
        private String variable;
        private boolean person;
    }

    @Data
    @AllArgsConstructor
    public class LogMessage {
        private String message;
        private int level = 0;
    }
}
