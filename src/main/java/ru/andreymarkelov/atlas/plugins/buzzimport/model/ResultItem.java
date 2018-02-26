package ru.andreymarkelov.atlas.plugins.buzzimport.model;

public class ResultItem {
    private final int rowNum;
    private final String status;

    public ResultItem(int rowNum, String status) {
        this.rowNum = rowNum;
        this.status = status;
    }

    public int getRowNum() {
        return rowNum;
    }

    public String getStatus() {
        return status;
    }

    @Override
    public String toString() {
        return "ResultItem{" +
                "rowNum=" + rowNum +
                ", status='" + status + '\'' +
                '}';
    }
}
