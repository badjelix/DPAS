package library;

import org.json.simple.JSONObject;

import java.io.Serializable;
import java.security.PublicKey;

public class Response implements Serializable {

    private boolean success;
    private JSONObject jsonObject;
    private byte[] nonce = null;

    private int errorCode = 0;
    private int ts = 0;
    private int rid = 0;
    private int port = 0;
    private PublicKey serverKey;


    public Response(byte[] nonce) {
        this.nonce = nonce;
    }

    public Response(boolean success, byte[] nonce) {
        this.success = success;
        this.nonce = nonce;

    }

    public Response(boolean success, byte[] nonce, int ts) {
        this.success = success;
        this.nonce = nonce;
        this.ts = ts;
    }

    public Response(boolean success, byte[] nonce, PublicKey serverKey) {
        this.success = success;
        this.nonce = nonce;
        this.serverKey = serverKey;
    }

    public Response(boolean success, int errorCode, byte[] nonce) {

        this.success = success;
        this.errorCode = errorCode;
        this.nonce = nonce;
    }

    public Response(boolean success, JSONObject object, byte[] nonce, int rid) {
        this.success = success;
        this.jsonObject = object;
        this.nonce = nonce;
        this.rid = rid;
    }
    

    //VALUE

    public Response(boolean success, int rid, int ts, byte[] nonce, JSONObject object, int port){
        this.success = success;
        this.jsonObject = object;
        this.nonce = nonce;
        this.rid = rid;
        this.ts = ts;
        this.port = port;
    }
    
    public PublicKey getServerKey() {
    	return serverKey;
    }
    
    public void setServerKey(PublicKey serverKey) {
    	this.serverKey = serverKey;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getRid() {
        return rid;
    }

    public void setRid(int rid) {
        this.rid = rid;
    }

    public int getTs() {
        return ts;
    }

    public void setTs(int ts) {
        this.ts = ts;
    }

    public JSONObject getJsonObject() {
        return jsonObject;
    }

    public void setJsonObject(JSONObject jsonObject) {
        this.jsonObject = jsonObject;
    }

    public Response(boolean success){
        this.success = success;
    }

    public boolean getSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public int getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(int errorCode) {
        this.errorCode = errorCode;
    }

    public byte[] getNonce() {
        return nonce;
    }

    public void setNonce(byte[] nonce) {
        this.nonce = nonce;
    }

    @Override
    public String toString() {
        String s ="";
        s += this.success + " ";

        if(this.jsonObject != null){
            s += this.jsonObject.toJSONString() + " ";
        }

        if(this.errorCode != 0){
            s += this.errorCode + " ";
        }

        if(this.ts != 0){
            s += this.ts + " ";
        }

        if(this.rid != 0){
            s += this.rid + " ";
        }

        s += '\n';

        return s;
    }
}
