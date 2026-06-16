package com.fuelize.model;

public class OrderData {

    private String orderNo;
    private String orderQty;
    private String otp;
    private String rfidTag;
    private String decantQty;
    private String startTotalizer;
    private String endTotalizer;
    private String status;
    private String timestamp;
    private String skipAuthentication;

    public OrderData() {
    }

    public String getOrderNo() {
        return orderNo;
    }
    public void setOrderNo(String order_no) {
        this.orderNo = order_no;
    }

    public String getorderQty() {
        return orderQty;
    }
    public void setorderQty(String order_qty) {
        this.orderQty = order_qty;
    }
    public String getOtp() {
        return otp;
    }
    public void setOtp(String otp) {
        this.otp = otp;
    }
    public String getRfidTag() {
        return rfidTag;
    }
    public void setRfidTag(String rfid_tag) {
        this.rfidTag = rfid_tag;
    }

    public String getDecantQty() {
        return decantQty;
    }
    public void setDecantQty(String decant_qty) {
        this.decantQty = decant_qty;
    }
    public String getStartTotalizer() {
        return startTotalizer;
    }
    public void setStartTotalizer(String start_totalizer) {
        this.startTotalizer = start_totalizer;
    }
    public String getEndTotalizer() {
        return endTotalizer;
    }
    public void setEndTotalizer(String end_totalizer) {
        this.endTotalizer = end_totalizer;
    }
    public String getStatus() {
        return status;
    }
    public void setStatus(String status) {
        this.status = status;
    }

    public String getTimestamp() {
        return timestamp;
    }
    public void setTimestamp(String ts) {
        this.timestamp = ts;
    }

    public String getSkipAuthentication() {
        return skipAuthentication;
    }
    public void setSkipAuthentication(String SkipAuth) {
        this.skipAuthentication = SkipAuth;
    }
}
