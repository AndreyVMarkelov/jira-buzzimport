package ru.andreymarkelov.atlas.plugins.buzzimport.webwork;

import java.io.File;
import java.io.StringReader;
import java.sql.Timestamp;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.atlassian.jira.event.type.EventDispatchOption;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.ModifiedValue;
import com.atlassian.jira.issue.MutableIssue;
import com.atlassian.jira.issue.customfields.impl.CascadingSelectCFType;
import com.atlassian.jira.issue.customfields.impl.DateCFType;
import com.atlassian.jira.issue.customfields.impl.DateTimeCFType;
import com.atlassian.jira.issue.customfields.impl.LabelsCFType;
import com.atlassian.jira.issue.customfields.impl.NumberCFType;
import com.atlassian.jira.issue.customfields.impl.SelectCFType;
import com.atlassian.jira.issue.customfields.impl.UserCFType;
import com.atlassian.jira.issue.customfields.manager.OptionsManager;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.index.IssueIndexManager;
import com.atlassian.jira.issue.label.Label;
import com.atlassian.jira.issue.label.LabelManager;
import com.atlassian.jira.issue.util.DefaultIssueChangeHolder;
import com.atlassian.jira.security.groups.GroupManager;
import com.atlassian.jira.security.xsrf.RequiresXsrfCheck;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.web.action.JiraWebActionSupport;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.andreymarkelov.atlas.plugins.buzzimport.manager.StorageManager;
import ru.andreymarkelov.atlas.plugins.buzzimport.model.ResultItem;
import webwork.multipart.MultiPartRequestWrapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.Optional.ofNullable;

import static com.atlassian.jira.permission.GlobalPermissionKey.ADMINISTER;
import static com.atlassian.jira.web.action.setup.AbstractSetupAction.DEFAULT_GROUP_ADMINS;
import static com.google.common.collect.Sets.newHashSet;
import static org.apache.commons.csv.CSVFormat.DEFAULT;
import static org.apache.commons.csv.CSVParser.parse;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.apache.commons.lang3.StringUtils.split;
import static org.apache.commons.lang3.StringUtils.trimToEmpty;
import static webwork.action.ServletActionContext.getMultiPartRequest;

public class BuzzImportUploadAdminAction extends JiraWebActionSupport {
    private static final Logger log = LoggerFactory.getLogger(BuzzImportUploadAdminAction.class);

    private static final Set<String> commonFields = newHashSet("key", "summary", "description", "duedate", "assignee", "labels");

    private static final DateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");
    private static final DateFormat DATETIME_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private final CustomFieldManager customFieldManager;
    private final IssueManager issueManager;
    private final OptionsManager optionsManager;
    private final GroupManager groupManager;
    private final LabelManager labelManager;
    private final IssueIndexManager issueIndexManager;
    private final StorageManager storageManager;

    private List<ResultItem> resultItems = Collections.emptyList();
    private String fatalError = "";
    private String lastMapping;

    public BuzzImportUploadAdminAction(
            CustomFieldManager customFieldManager,
            IssueManager issueManager,
            OptionsManager optionsManager,
            GroupManager groupManager,
            LabelManager labelManager,
            IssueIndexManager issueIndexManager,
            StorageManager storageManager) {
        this.customFieldManager = customFieldManager;
        this.issueManager = issueManager;
        this.optionsManager = optionsManager;
        this.groupManager = groupManager;
        this.labelManager = labelManager;
        this.issueIndexManager = issueIndexManager;
        this.storageManager = storageManager;
    }

    @Override
    protected void doValidation() {
        MultiPartRequestWrapper multiPartRequestWrapper = getMultiPartRequest();
        String mapping = multiPartRequestWrapper.getParameterValues("mapping")[0];
        String fileName = multiPartRequestWrapper.getFilesystemName("uploadFile");

        if (!fileName.endsWith(".csv") && !fileName.endsWith(".xlsx")) {
            addErrorMessage(getText(getText("ru.andreymarkelov.atlas.plugins.buzzimport.upload.error.fileformat")));
        }

        Properties properties = new Properties();
        try {
            properties.load(new StringReader(mapping));
            properties.stringPropertyNames().forEach(key -> {
                if (!validField(key)) {
                    addErrorMessage(getText("ru.andreymarkelov.atlas.plugins.buzzimport.upload.error.fields", key));
                }
            });
        } catch (Exception ex) {
            addErrorMessage(getText("ru.andreymarkelov.atlas.plugins.buzzimport.upload.error.properties"));
        }
    }

    @Override
    public String doDefault() {
        if (!hasAdminPermission()) {
            return PERMISSION_VIOLATION_RESULT;
        }
        lastMapping = storageManager.getLastMapping();
        return INPUT;
    }

    @Override
    @RequiresXsrfCheck
    public String doExecute() {
        if (!hasAdminPermission()) {
            return PERMISSION_VIOLATION_RESULT;
        }

        resultItems = new ArrayList<>();
        MultiPartRequestWrapper multiPartRequestWrapper = getMultiPartRequest();
        String mapping = multiPartRequestWrapper.getParameterValues("mapping")[0];
        storageManager.setLastMapping(mapping);
        String fileName = multiPartRequestWrapper.getFilesystemName("uploadFile");
        File file = multiPartRequestWrapper.getFile("uploadFile");
        try {
            Properties properties = new Properties();
            properties.load(new StringReader(mapping));
            if (log.isDebugEnabled()) {
                log.debug("Parsed mappings: {}", properties);
            }
            List<Map<String, String>> rows = fileName.endsWith(".csv") ? readCsv(file) : readXLSX(file);
            if (!rows.isEmpty()) {
                int rowNum = 1;
                for (Map<String, String> row : rows) {
                    if (log.isDebugEnabled()) {
                        log.debug("Processing row: {}", row);
                    }

                    String keyMapping = row.get(properties.getProperty("key"));
                    String summaryMapping = row.get(properties.getProperty("summary"));
                    String descMapping = row.get(properties.getProperty("description"));
                    String dueDateMapping = row.get(properties.get("duedate"));
                    String assigneeMapping = row.get(properties.get("assignee"));
                    String labelsMapping = row.get(properties.get("labels"));

                    if (isBlank(keyMapping)) {
                        resultItems.add(new ResultItem(rowNum++, "No issue key in row"));
                    } else {
                        MutableIssue mutableIssue = issueManager.getIssueObject(keyMapping);
                        if (mutableIssue != null) {
                            if (isNotBlank(summaryMapping)) {
                                mutableIssue.setSummary(summaryMapping);
                            }
                            if (isNotBlank(descMapping)) {
                                mutableIssue.setDescription(descMapping);
                            }
                            if (isNotBlank(dueDateMapping)) {
                                mutableIssue.setDueDate(new Timestamp(DATE_FORMAT.parse(dueDateMapping).getTime()));
                            }
                            if (isNotBlank(assigneeMapping)) {
                                mutableIssue.setAssigneeId(assigneeMapping);
                            }

                            Enumeration enumeration = properties.propertyNames();
                            while (enumeration.hasMoreElements()) {
                                String key = (String) enumeration.nextElement();
                                String value = row.get(properties.getProperty(key));
                                if (log.isDebugEnabled()) {
                                    log.debug("Field mappings: {}, {}", key, value);
                                }

                                if (!commonFields.contains(key) && value != null) {
                                    CustomField customField = customFieldManager.getCustomFieldObject(key);
                                    if (customField != null) {
                                        updateCustomField(mutableIssue, customField, value);
                                    } else {
                                        customField = customFieldManager.getCustomFieldObjectByName(key);
                                        if (customField != null) {
                                            updateCustomField(mutableIssue, customField, value);
                                        }
                                    }
                                }
                            }

                            ApplicationUser user = groupManager.getUsersInGroup(DEFAULT_GROUP_ADMINS).iterator().next();
                            issueManager.updateIssue(user, mutableIssue, EventDispatchOption.ISSUE_UPDATED, false);

                            if (isNotBlank(labelsMapping)) {
                                try {
                                    labelManager.setLabels(user, mutableIssue.getId(), new LinkedHashSet<>(asList(split(labelsMapping, " "))), false, false);
                                } catch (Exception ex) {
                                    log.warn("Error setup labels", ex);
                                }
                            }
                            resultItems.add(new ResultItem(rowNum++, "Issue " + keyMapping + " successfully updated."));
                            issueIndexManager.reIndex(mutableIssue);
                        } else {
                            resultItems.add(new ResultItem(rowNum++, "Issue key " + keyMapping + " doesn't exist."));
                        }
                    }
                }
            }
        } catch (Exception e) {
            fatalError = e.getMessage();
        }

        return SUCCESS;
    }

    private void updateCustomField(MutableIssue mutableIssue, CustomField customField, String value) throws Exception {
        if (StringUtils.isBlank(value)) {
            customField.removeValueFromIssueObject(mutableIssue);
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug("Found field: {}", customField.getFieldName());
        }
        if (customField.getCustomFieldType().getClass().isAssignableFrom(SelectCFType.class)) {
            updateSelectField(mutableIssue, customField, value);
        } else if (customField.getCustomFieldType().getClass().isAssignableFrom(CascadingSelectCFType.class)) {
            updateCascadingField(mutableIssue, customField, value);
        } else if (customField.getCustomFieldType().getClass().isAssignableFrom(NumberCFType.class)) {
            updateNumberField(mutableIssue, customField, value);
        } else if (customField.getCustomFieldType().getClass().isAssignableFrom(UserCFType.class)) {
            updateUserField(mutableIssue, customField, value);
        } else if (customField.getCustomFieldType().getClass().isAssignableFrom(DateCFType.class)) {
            updateDateField(mutableIssue, customField, value);
        } else if (customField.getCustomFieldType().getClass().isAssignableFrom(DateTimeCFType.class)) {
            updateDateTimeField(mutableIssue, customField, value);
        } else if (customField.getCustomFieldType().getClass().isAssignableFrom(LabelsCFType.class)) {
            updateLabelsNumberField(mutableIssue, customField, value);
        } else {
            updateTextField(mutableIssue, customField, value);
        }
    }

    /**
     * Set value for text fields.
     */
    private void updateTextField(MutableIssue mutableIssue, CustomField customField, String value) {
        mutableIssue.setCustomFieldValue(customField, value);
    }

    /**
     * Set value for number fields.
     */
    private void updateNumberField(MutableIssue mutableIssue, CustomField customField, String value) {
        mutableIssue.setCustomFieldValue(customField, Double.valueOf(value));
    }

    /**
     * Set value for labels fields.
     */
    private void updateLabelsNumberField(MutableIssue mutableIssue, CustomField customField, String value) {
        Set<Label> labels = Stream.of(split(value, " "))
                .map(x -> new Label(null, mutableIssue.getId(), customField.getIdAsLong(), x))
                .collect(Collectors.toSet());
        mutableIssue.setCustomFieldValue(customField, labels);
    }

    /**
     * Set value for date fields.
     */
    private void updateDateField(MutableIssue mutableIssue, CustomField customField, String value) throws Exception {
        mutableIssue.setCustomFieldValue(customField, new Timestamp(DATE_FORMAT.parse(value).getTime()));
    }

    /**
     * Set value for datetime fields.
     */
    private void updateDateTimeField(MutableIssue mutableIssue, CustomField customField, String value) throws Exception {
        mutableIssue.setCustomFieldValue(customField, new Timestamp(DATETIME_FORMAT.parse(value).getTime()));
    }

    /**
     * Set value for user fields.
     */
    private void updateUserField(MutableIssue mutableIssue, CustomField customField, String value) {
        ApplicationUser applicationUser = getUserManager().getUserByName(value);
        if (applicationUser == null) {
            applicationUser = getUserManager().getUserByKey(value);
        }
        mutableIssue.setCustomFieldValue(customField, applicationUser);
    }

    /**
     * Set value for select fields.
     */
    private void updateSelectField(MutableIssue mutableIssue, CustomField customField, String value) {
        optionsManager.findByOptionValue(value).stream()
                .filter(x -> x.getRelatedCustomField().getFieldId().equals(customField.getId()))
                .findFirst()
                .ifPresent(x -> mutableIssue.setCustomFieldValue(customField, x));
    }

    /**
     * Set value for cascading select fields.
     */
    private void updateCascadingField(MutableIssue mutableIssue, CustomField customField, String value) {
        String[] values = split(value, ",");
        if (log.isDebugEnabled()) {
            log.debug("Trying to find parent option: {}", values[0]);
        }
        optionsManager.getOptions(customField.getRelevantConfig(mutableIssue)).stream()
                .filter(x -> trimToEmpty(values[0]).equals(trimToEmpty(x.getValue())))
                .findFirst()
                .ifPresent(x -> {
                    if (log.isDebugEnabled()) {
                        log.debug("Found parent option: {}:{}", x.getOptionId(), x.getValue());
                    }

                    if (values.length > 1) {
                        x.getChildOptions().stream()
                                .filter(y -> trimToEmpty(y.getValue()).equals(trimToEmpty(values[1])))
                                .findFirst()
                                .ifPresent(y -> {
                                    if (log.isDebugEnabled()) {
                                        log.debug("Found children option: {}:{}", y.getOptionId(), y.getValue());
                                    }

                                    DefaultIssueChangeHolder changeHolder = new DefaultIssueChangeHolder();
                                    Map<String, Object> newValue = new HashMap<>();
                                    newValue.put(null, x);
                                    newValue.put("1", y);
                                    customField.updateValue(null, mutableIssue, new ModifiedValue(mutableIssue.getCustomFieldValue(customField), newValue), changeHolder);
                                });
                    } else {
                        DefaultIssueChangeHolder changeHolder = new DefaultIssueChangeHolder();
                        Map<String, Object> newValue = new HashMap<>();
                        newValue.put(null, x);
                        customField.updateValue(null, mutableIssue, new ModifiedValue(mutableIssue.getCustomFieldValue(customField), newValue), changeHolder);
                    }
                });
    }

    private boolean hasAdminPermission() {
        return ofNullable(getLoggedInUser())
                .map(x -> getGlobalPermissionManager().hasPermission(ADMINISTER, x))
                .orElse(false);
    }

    private boolean validField(String fieldName) {
        boolean isCommon = commonFields.stream().anyMatch(x -> x.equalsIgnoreCase(fieldName));
        if (isCommon) {
            return true;
        }

        if (customFieldManager.getCustomFieldObject(fieldName) != null) {
            return true;
        }
        if (customFieldManager.getCustomFieldObjectByName(fieldName) != null) {
            return true;
        }
        return false;
    }

    public List<ResultItem> getResultItems() {
        return resultItems;
    }

    public String getFatalError() {
        return fatalError;
    }

    private static List<Map<String, String>> readXLSX(File excel) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(excel)) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet.getLastRowNum() < 1) {
                return new ArrayList<>();
            }

            List<String> headers = new ArrayList<>();
            sheet.getRow(0).iterator().forEachRemaining(cell -> headers.add(readCell(cell)));

            List<Map<String, String>> rows = new ArrayList<>();
            AtomicInteger rowNum = new AtomicInteger(0);
            sheet.iterator().forEachRemaining(row -> {
                if (rowNum.get() != 0) {
                    Map<String, String> rowMap = new LinkedHashMap<>();
                    for (int i = 0; i < headers.size(); i++) {
                        rowMap.put(headers.get(i), readCell(row.getCell(i)));
                    }
                    rows.add(rowMap);
                }
                rowNum.incrementAndGet();
            });
            return rows;
        }
    }

    private static String readCell(Cell cell) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == Cell.CELL_TYPE_STRING || cell.getCellType() == Cell.CELL_TYPE_BLANK) {
            return cell.getStringCellValue();
        } else if (cell.getCellType() == Cell.CELL_TYPE_NUMERIC) {
            cell.setCellType(Cell.CELL_TYPE_STRING);
            return cell.getStringCellValue();
        } else {
            throw new RuntimeException("Only string and numeric cells supported");
        }
    }

    private static List<Map<String, String>> readCsv(File csv) throws Exception {
        CSVParser csvRecords = parse(csv, UTF_8, DEFAULT.withSkipHeaderRecord(false));
        List<Map<String, String>> rows = new ArrayList<>();
        List<String> headers = new ArrayList<>();
        AtomicInteger rowNum = new AtomicInteger(0);
        csvRecords.iterator().forEachRemaining(row -> {
            if (rowNum.get() != 0) {
                Map<String, String> rowMap = new LinkedHashMap<>();
                AtomicInteger headersCounter = new AtomicInteger(0);
                row.iterator().forEachRemaining(x -> rowMap.put(headers.get(headersCounter.getAndIncrement()), x));
                rows.add(rowMap);
            } else {
                row.iterator().forEachRemaining(headers::add);
            }
            rowNum.incrementAndGet();
        });
        return rows;
    }

    public String getLastMapping() {
        return lastMapping;
    }
}
