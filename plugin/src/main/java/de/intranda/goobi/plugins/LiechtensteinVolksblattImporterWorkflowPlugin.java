package de.intranda.goobi.plugins;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.collections4.queue.CircularFifoQueue;
import org.apache.commons.configuration.HierarchicalConfiguration;
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
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
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
    private static final Pattern YEAR_PATTERN = Pattern.compile("2\\d{3}");
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private static final String NEWSPAPER_TYPE = "Newspaper";
    private static final String NEWSPAPER_VOLUME_TYPE = "NewspaperVolume";
    private static final String NEWSPAPER_ISSUE_TYPE = "NewspaperIssue";

    private static final Set<String> ISSUES_SET = new HashSet<>();

    //    private static final String YEAR_PATTERN_STRING = "2\\d{3}";

    @Getter
    private String title = "intranda_workflow_liechtenstein_volksblatt_importer";
    private long lastPush = System.currentTimeMillis();
    @Getter
    private List<ImportSet> importSets;
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
    private Queue<LogMessage> logQueue = new CircularFifoQueue<LogMessage>(48);
    private String importFolder;
    private String workflow;
    private String publicationType;
    
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
        publicationType = ConfigPlugins.getPluginConfig(title).getString("publicationType");
        
        // read list of mapping configuration
        importSets = new ArrayList<ImportSet>();
        List<HierarchicalConfiguration> mappings = ConfigPlugins.getPluginConfig(title).configurationsAt("importSet");
        for (HierarchicalConfiguration node : mappings) {
            String settitle = node.getString("[@title]", "-");
            String source = node.getString("[@source]", "-");
            String target = node.getString("[@target]", "-");
            boolean person = node.getBoolean("[@person]", false);
            importSets.add(new ImportSet(settitle, source, target, person));
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
     * 
     * @param importset
     */
    public void startImport(ImportSet importset) {
    	updateLog("Start import for: " + importset.getTitle());
        progress = 0;
        BeanHelper bhelp = new BeanHelper();
        
        // run the import in a separate thread to allow a dynamic progress bar
        run = true;
        Runnable runnable = () -> {
            
            // read input file
            try {
            	updateLog("Run through all import files");
                int start = 0;

                List<Path> pdfFiles = storageProvider.listFiles(importFolder);
                int end = pdfFiles.size();

                itemsTotal = end - start;
                itemCurrent = start;
                
                // run through import files (e.g. from importFolder)
                for (Path pdfFile : pdfFiles) {
                    Thread.sleep(100);
                    if (!run) {
                        break;
                    }

                    //                    String fileName = pdfFile.getFileName().toString();
                    //                    NewspaperPage page = new NewspaperPage(fileName);

                    //                    String fileDate = getDateFromFileName(fileName);
                    //                    log.debug("fileDate = " + fileDate);

                    // TODO: check blankness of fileDate

                    //                    String processName = createProcessName(fileDate);
                    //                    updateLog("Start importing: " + processName, 1);

                    // TODO: process pdfFile

                    //                    boolean success = addPdfFileToProcess(bhelp, processName, pdfFile);
                    //                    boolean success = addPdfFileToProcess(bhelp, fileDate, pdfFile);
                    boolean success = addPdfFileToProcess(bhelp, pdfFile);
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

    private String getDateFromFileName(String fileName) {
        Matcher matcher = DATE_PATTERN.matcher(fileName);
        return matcher.find() ? matcher.group() : "";
    }

    /**
     * create the title for the new process
     * 
     * @param dateString
     * @return new process title
     */
    private String createProcessName(String dateString) {
        log.debug("creating process name for: " + dateString);
        log.debug(YEAR_PATTERN.toString());
        // create a process name using the year encoded in the fileName
        Matcher matcher = YEAR_PATTERN.matcher(dateString);

        return matcher.find() ? matcher.group() : "";
    }

    private boolean addPdfFileToProcess(BeanHelper bhelp, Path pdfFilePath) {
        //        String fileName = pdfFilePath.getFileName().toString();
        NewspaperPage page = new NewspaperPage(pdfFilePath);
        
        String processName = page.getYear();
        updateLog("Start importing: " + processName, 1);

        // check existen of process
        Process existingProcess = getProcessByName(processName);
        boolean processExists = existingProcess != null;

        return processExists ? tryUpdateOldProcess(existingProcess, page) : tryCreateAndSaveNewProcess(bhelp, processName, page);
    }

    //    private boolean addPdfFileToProcess(BeanHelper bhelp, String fileDate, Path pdfFilePath) {
    //        // prepare process name
    //        String processName = createProcessName(fileDate);
    //        updateLog("Start importing: " + processName, 1);
    //
    //        // check existence of process
    //        Process existingProcess = getProcessByName(processName);
    //        //        boolean processExists = checkExistenceOfProcess(processName);
    //        boolean processExists = existingProcess != null;
    //
    //        return processExists ? tryUpdateOldProcess(existingProcess, pdfFilePath) : tryCreateAndSaveNewProcess(bhelp, processName, pdfFilePath);
    //    }

    //    private boolean checkExistenceOfProcess(String processName) {
    //        log.debug("Counting number of processes for " + processName);
    //        return ProcessManager.getNumberOfProcessesWithTitle(processName) != 0;
    //    }

    private Process getProcessByName(String processName) {
        log.debug("Trying to retrieve the process if it exists.");
        // null will be returned if no such process exists
        return ProcessManager.getProcessByTitle(processName);
    }

    private boolean tryUpdateOldProcess(Process process, NewspaperPage page) {
        log.debug("Updating process: " + process.getTitel());
        Path pdfFilePath = page.getFilePath();
        // TODO: metadata
        try {
            updateMetadataOfProcess(process, page);
        } catch (ReadException | IOException | SwapException e1) {
            // TODO Auto-generated catch block
            // read Fileformat
            e1.printStackTrace();

        } catch (PreferencesException e) {
            // TODO Auto-generated catch block
            // DigitalDocument
            e.printStackTrace();

        } catch (Exception e) {
            log.debug("Unknown exception caught while updating process: " + process.getTitel());
            e.printStackTrace();
        }

        // copy files into the media folder of the process
        try {
            copyMediaFile(process, pdfFilePath);
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
            Fileformat fileformat = process.readMetadataFile(); // ERROR: The process {} cannot be loaded as there is no format -.:

            log.debug("fileformat is " + (fileformat == null ? "" : "NOT") + " null");

            // update metadata
            DigitalDocument dd = fileformat.getDigitalDocument();
            DocStruct logical = dd.getLogicalDocStruct();
            DocStruct volume = logical.getAllChildren().get(0);

            // create and add new issue
            DocStruct issue = createNewIssue(prefs, dd, page);
            //            if (issue == null) {
            //                // TODO: error happened
            //                //                return;
            //                log.debug("Issue already exists: " + page.getDate());
            //            }

            if (issue != null) {
                try {
                    volume.addChild(issue);
                } catch (TypeNotAllowedAsChildException e) {
                    // TODO
                    e.printStackTrace();
                    return;
                }

            } else {
                log.debug("Issue already exists: " + page.getDate());
            }

            //            try {
            //                if (issue != null) {
            //                    volume.addChild(issue);
            //                }
            //
            //            } catch (TypeNotAllowedAsChildException e) {
            //                // TODO Auto-generated catch block
            //                e.printStackTrace();
            //                return;
            //            }

            // write changes into file
            process.writeMetadataFile(fileformat);

        } catch (Exception e) {
            log.debug("Exception caught while updating metadata of process: " + process.getTitel());
            e.printStackTrace();
        }

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

        // create and add new issue

        // save the process
        Process process = createAndSaveNewProcess(bhelp, template, processName, fileformat);
        if (process == null) {
            // error heppened while saving
            return false;
        }

        // copy files into the media folder of the process
        try {
            copyMediaFile(process, page.getFilePath());
        } catch (IOException | SwapException | DAOException e) {
            String message = "Error while trying to copy files into the media folder: " + e.getMessage();
            reportError(message);
            return false;
        }

        //        try {
        //            process.writeMetadataFile(fileformat);
        //        } catch (WriteException | PreferencesException | IOException | SwapException e) {
        //            // TODO Auto-generated catch block
        //            e.printStackTrace();
        //            return false;
        //        }

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

            // add the logical basics
            DocStruct logical = dd.createDocStruct(prefs.getDocStrctTypeByName(publicationType)); // publicationType is Newspaper
            String identifier = processName + "_newspaper";
            logical.setIdentifier(identifier);
            dd.setLogicalDocStruct(logical);

            // prepare the volume
            DocStruct volume = dd.createDocStruct(prefs.getDocStrctTypeByName(NEWSPAPER_VOLUME_TYPE));
            String volumeId = "Volume_ID"; // TODO: change this
            // NewspaperVolume should have a CatalogIDDigital that is different from the one of Newspaper
            MetadataType catalogIdDigitalType = prefs.getMetadataTypeByName("CatalogIDDigital");
            Metadata volumeIdMetadata = createMetadata(catalogIdDigitalType, volumeId, false);
            volume.addMetadata(volumeIdMetadata);

            //            volume.addReferenceTo(logical, "logical_physical");
            //            volume.setReferenceToAnchor(logical.getIdentifier());
            log.debug("adding DocStruct child: " + NEWSPAPER_VOLUME_TYPE);
            try {
                logical.addChild(volume);
                log.debug("logical has identifier = " + logical.getIdentifier());
                //                volume.setReferenceToAnchor(logical.getIdentifier());

            } catch (TypeNotAllowedAsChildException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
                return null;
            }

            //            logical.addChild(new DocStruct());
            DocStruct issue = createNewIssue(prefs, dd, page);
            if (issue == null) {
                // TODO: error happened
                return null;
            }

            try {
                volume.addChild(issue);

            } catch (TypeNotAllowedAsChildException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
                return null;
            }


            // create the metadata fields by reading the config (and get content from the content files of course)
            createMetadataFields(prefs, logical, importSets);

            return fileformat;

        } catch (PreferencesException | TypeNotAllowedForParentException | MetadataTypeNotAllowedException | IncompletePersonObjectException e) {
            String message = "Error while preparing the Fileformat for the new process: " + e.getMessage();
            reportError(message);
            return null;
        }

    }

    /**
     * create all metadata fields
     * 
     * @param prefs Prefs
     * @param ds DocStruct
     * @param importSets list of ImportMetadata
     */
    private void createMetadataFields(Prefs prefs, DocStruct ds, List<ImportSet> importSets) {
        for (ImportSet importSet : importSets) {
            // prepare the MetadataType
            String target = importSet.getTarget();
            MetadataType targetType = prefs.getMetadataTypeByName(target);
            String value = importSet.getSource();
            log.debug("targetType = " + targetType);

            boolean isPerson = importSet.isPerson();

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
        String titleValue = page.getDate();
        // check existence of this issue
        if (ISSUES_SET.contains(titleValue)) {
            // issue already exists, no need to create again
            return null;
        }

        try {
            DocStruct issue = dd.createDocStruct(prefs.getDocStrctTypeByName(NEWSPAPER_ISSUE_TYPE));
            //            volume.addChild(issue);
            String targetTypeName = "TitleDocMainShort";
            MetadataType targetType = prefs.getMetadataTypeByName(targetTypeName);
            String value = "HELLO_world";
            Metadata md = createMetadata(targetType, value, false);
            issue.addMetadata(md);

            // TitleDocMain
            String titleTypeName = "TitleDocMain";
            MetadataType titleType = prefs.getMetadataTypeByName(titleTypeName);
            //            String titleValue = page.getDate();
            Metadata titleMetadata = createMetadata(titleType, titleValue, false);
            issue.addMetadata(titleMetadata);

            ISSUES_SET.add(titleValue);

            return issue;

        } catch (TypeNotAllowedForParentException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            return null;

        } catch (MetadataTypeNotAllowedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            return null;
        }
    }

    private Process createAndSaveNewProcess(BeanHelper bhelp, Process template, String processName, Fileformat fileformat) {
        // save the process
        Process process = bhelp.createAndSaveNewProcess(template, processName, fileformat);

        // add some properties
        bhelp.EigenschaftHinzufuegen(process, "Template", template.getTitel());
        bhelp.EigenschaftHinzufuegen(process, "TemplateID", "" + template.getId());

        try {
            ProcessManager.saveProcess(process);
            //            process.writeMetadataFile(fileformat);

        } catch (DAOException e) {
            String message = "Error while trying to save the process: " + e.getMessage();
            reportError(message);
            return null;
        }
//        } catch (WriteException | PreferencesException | IOException | SwapException e) {
//            // TODO Auto-generated catch block
//            e.printStackTrace();
//            return null;
//        }

        return process;
    }

    /**
     * copy the images from importFolder to media folders of the process
     * 
     * @param process Process whose media folder is targeted
     * @throws IOException
     * @throws SwapException
     * @throws DAOException
     */
    private void copyMediaFile(Process process, Path pdfFilePath) throws IOException, SwapException, DAOException {
        // if media files are given, import these into the media folder of the process
        updateLog("Start copying media files");
        // prepare the directories
        String mediaBase = process.getImagesTifDirectory(false);
        storageProvider.createDirectories(Path.of(mediaBase));
        File file = pdfFilePath.toFile();
        if (file.canRead()) {
            String fileName = pdfFilePath.getFileName().toString();
            log.debug("fileName = " + fileName);
            Path targetPath = Path.of(mediaBase, fileName);
            //            storageProvider.move(pdfFilePath, targetPath);
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
    public class ImportSet {
        private String title;
        private String source;
        private String target;
        private boolean person;
    }

    @Data
    @AllArgsConstructor
    public class LogMessage {
        private String message;
        private int level = 0;
    }
}
