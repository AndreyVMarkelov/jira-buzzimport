package ru.andreymarkelov.atlas.plugins.buzzimport.webwork;

import com.atlassian.jira.bc.issue.IssueService;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.IssueInputParameters;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.MutableIssue;
import com.atlassian.jira.issue.customfields.impl.SelectCFType;
import com.atlassian.jira.issue.customfields.manager.OptionsManager;
import com.atlassian.jira.issue.customfields.option.Option;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.security.groups.GroupManager;
import com.atlassian.jira.security.xsrf.RequiresXsrfCheck;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.web.action.JiraWebActionSupport;
import com.google.common.collect.Sets;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import ru.andreymarkelov.atlas.plugins.buzzimport.model.ResultItem;
import webwork.multipart.MultiPartRequestWrapper;

import java.io.File;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.atlassian.jira.permission.GlobalPermissionKey.ADMINISTER;
import static com.atlassian.jira.web.action.setup.AbstractSetupAction.DEFAULT_GROUP_ADMINS;
import static java.util.Optional.ofNullable;
import static webwork.action.ServletActionContext.getMultiPartRequest;

public class BuzzImportUploadAdminAction extends JiraWebActionSupport {
    private static final Set<String> commonFields = Sets.newHashSet("key", "summary", "description");

    private final CustomFieldManager customFieldManager;
    private final IssueManager issueManager;
    private final IssueService issueService;
    private final OptionsManager optionsManager;
    private final GroupManager groupManager;

    private List<ResultItem> resultItems = Collections.emptyList();
    private String fatalError = "";

    public BuzzImportUploadAdminAction(
            CustomFieldManager customFieldManager,
            IssueManager issueManager,
            IssueService issueService,
            OptionsManager optionsManager,
            GroupManager groupManager) {
        this.customFieldManager = customFieldManager;
        this.issueManager = issueManager;
        this.issueService = issueService;
        this.optionsManager = optionsManager;
        this.groupManager = groupManager;
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
        File file = multiPartRequestWrapper.getFile("uploadFile");
        try {
            Properties properties = new Properties();
            properties.load(new StringReader(mapping));
            List<Map<String, String>> rows = readXLSX(file);

            if (!rows.isEmpty()) {
                int rowNum = 0;
                for (Map<String, String> row : rows) {
                    String keyMapping = row.get(properties.getProperty("key"));
                    String summaryMapping = row.get(properties.getProperty("summary"));
                    String descMapping = row.get(properties.getProperty("description"));

                    if (StringUtils.isBlank(keyMapping)) {
                        resultItems.add(new ResultItem(rowNum, "No issue key in row"));
                    } else {
                        MutableIssue mutableIssue = issueManager.getIssueObject(keyMapping);
                        if (mutableIssue != null) {
                            IssueInputParameters issueInputParameters = issueService.newIssueInputParameters();
                            if (StringUtils.isNotBlank(summaryMapping)) {
                                issueInputParameters.setSummary(summaryMapping);
                            }
                            if (StringUtils.isNotBlank(descMapping)) {
                                issueInputParameters.setDescription(descMapping);
                            }

                            Enumeration enumeration = properties.propertyNames();
                            while (enumeration.hasMoreElements()) {
                                String key = (String) enumeration.nextElement();
                                String value = row.get(properties.getProperty(key));
                                if (!commonFields.contains(key) && value != null) {
                                    CustomField customField = customFieldManager.getCustomFieldObject(key);
                                    if (customField != null) {
                                        String fieldId = customField.getId();
                                        Long fieldIdAsLong = customField.getIdAsLong();
                                        if (customField.getCustomFieldType().getClass().isAssignableFrom(SelectCFType.class)) {
                                            List<Option> options = optionsManager.findByOptionValue(value);
                                            options.stream()
                                                    .filter(x -> x.getRelatedCustomField().getFieldId().equals(fieldId))
                                                    .map(x -> x.getOptionId().toString())
                                                    .findFirst()
                                                    .ifPresent(x -> issueInputParameters.addCustomFieldValue(fieldIdAsLong, x));
                                        } else {
                                            issueInputParameters.addCustomFieldValue(fieldIdAsLong, value);
                                        }
                                    } else {
                                        customField = customFieldManager.getCustomFieldObjectByName(key);
                                        if (customField != null) {
                                            String fieldId = customField.getId();
                                            Long fieldIdAsLong = customField.getIdAsLong();
                                            if (customField.getCustomFieldType().getClass().isAssignableFrom(SelectCFType.class)) {
                                                List<Option> options = optionsManager.findByOptionValue(value);
                                                options.stream()
                                                        .filter(x -> x.getRelatedCustomField().getFieldId().equals(fieldId))
                                                        .map(x -> x.getOptionId().toString())
                                                        .findFirst()
                                                        .ifPresent(x -> issueInputParameters.addCustomFieldValue(fieldIdAsLong, x));
                                            } else {
                                                issueInputParameters.addCustomFieldValue(fieldIdAsLong, value);
                                            }
                                        }
                                    }
                                }
                            }

                            ApplicationUser user = groupManager.getUsersInGroup(DEFAULT_GROUP_ADMINS).iterator().next();
                            IssueService.UpdateValidationResult updateValidationResult = issueService.validateUpdate(user, mutableIssue.getId(), issueInputParameters);
                            if (updateValidationResult.isValid()) {
                                IssueService.IssueResult updateResult = issueService.update(user, updateValidationResult);
                                if (!updateResult.isValid()) {
                                    StringBuilder sb = new StringBuilder();
                                    for (String error : updateValidationResult.getErrorCollection().getErrorMessages()) {
                                        if (sb.length() > 0) {
                                            sb.append("\n");
                                        }
                                        sb.append(error);
                                    }
                                    resultItems.add(new ResultItem(rowNum + 1, sb.toString()));
                                } else {
                                    resultItems.add(new ResultItem(rowNum + 1, "Issue " + keyMapping + " successfully updated."));
                                }
                            } else {
                                StringBuilder sb = new StringBuilder();
                                for (String error : updateValidationResult.getErrorCollection().getErrorMessages()) {
                                    if (sb.length() > 0) {
                                        sb.append("\n");
                                    }
                                    sb.append(error);
                                }
                                for (Map.Entry<String, String> errorEntry : updateValidationResult.getErrorCollection().getErrors().entrySet()) {
                                    if (sb.length() > 0) {
                                        sb.append("\n");
                                    }
                                    sb.append(errorEntry.getKey()).append(": ").append(errorEntry.getValue());
                                }
                                resultItems.add(new ResultItem(rowNum + 1, sb.toString()));
                            }
                        } else {
                            resultItems.add(new ResultItem(rowNum + 1, "Issue key " + keyMapping + " doesn't exist."));
                        }
                    }
                }
            }
        } catch (Exception e) {
            fatalError = e.getMessage();
        }

        return SUCCESS;
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
                    AtomicInteger headersCounter = new AtomicInteger(0);
                    row.iterator().forEachRemaining(cell -> rowMap.put(headers.get(headersCounter.getAndIncrement()), readCell(cell)));
                    rows.add(rowMap);
                }
                rowNum.incrementAndGet();
            });
            return rows;
        }
    }

    private static String readCell(Cell cell) {
        if (cell.getCellType() == Cell.CELL_TYPE_STRING) {
            return cell.getStringCellValue();
        } else if (cell.getCellType() == Cell.CELL_TYPE_NUMERIC) {
            return String.valueOf(cell.getNumericCellValue());
        } else {
            throw new RuntimeException("Only string and numeric cells supported");
        }
    }
}
