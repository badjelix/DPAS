	package client;

import exceptions.*;
import library.Envelope;
import library.Request;
import library.Response;

import org.json.simple.JSONObject;

import java.io.*;
import java.net.Socket;
import java.security.*;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

    public class ClientEndpoint {

    private byte[][] serverNonce = null;
    private byte[][] clientNonce = null;

    private String serverAddress = null;

    private PrivateKey privateKey = null;
    private PublicKey publicKey = null;
    private PublicKey serverPublicKey = null;
    private String userName = null;
    private CryptoManager criptoManager = null;

    private int nFaults;
    private static final int PORT = 9000;

    private String registerErrorMessage = "There was a problem with your request, we cannot infer if you registered. Please try to login.";
    private String errorMessage = "There was a problem with your request. Please try again.";

    /*********** Simulated Attacks Flags ************/

    private boolean replayFlag = false;
    private boolean integrityFlag = false;

    /************************************************/

    public ClientEndpoint(String userName, String server, int faults){
    	criptoManager = new CryptoManager();
        setPrivateKey(criptoManager.getPrivateKeyFromKs(userName));
        setPublicKey(criptoManager.getPublicKeyFromKs(userName, userName));
        setServerPublicKey(criptoManager.getPublicKeyFromKs(userName, "server"));
        setUsername(userName);
        setServerAddress(server);
        setNFaults(faults);
        serverNonce = new byte[faults*3 + 1][];
        clientNonce = new byte[faults*3 + 1][];
    }

    public int getnFaults() {
        return nFaults;
    }

    public void setNFaults(int nFaults) {
        this.nFaults = nFaults;
    }

    public String getServerAddress() {
        return serverAddress;
    }

    public void setServerAddress(String serverAddress) {
        this.serverAddress = serverAddress;
    }

    public boolean isReplayFlag() {
        return replayFlag;
    }

    public void setReplayFlag(boolean replayFlag) {
        this.replayFlag = replayFlag;
    }

    public boolean isIntegrityFlag() {
		return integrityFlag;
	}

	public void setIntegrityFlag(boolean integrityFlag) {
		this.integrityFlag = integrityFlag;
	}

	public String getUsername() {
        return userName;
    }

    public void setUsername(String userName) {
        this.userName = userName;
    }

    public byte[] getServerNonce(int port) {
        return serverNonce[port - PORT];
    }

    public void setServerNonce(int port, byte[] serverNonce) {
        this.serverNonce[port - PORT] = serverNonce;
    }

    public byte[] getClientNonce(int port) {
        return clientNonce[port - PORT];
    }

    public void setClientNonce(int port, byte[] clientNonce) {
        this.clientNonce[port - PORT] = clientNonce;
    }

    public PrivateKey getPrivateKey(){
        return this.privateKey;
    }

    public void setPrivateKey(PrivateKey privateKey) {
        this.privateKey = privateKey;
    }

    public PublicKey getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(PublicKey publicKey) {
        this.publicKey = publicKey;
    }
    
    public PublicKey getServerPublicKey() {
        return serverPublicKey;
    }

    public void setServerPublicKey(PublicKey serverPublicKey) {
        this.serverPublicKey = serverPublicKey;
    }

    private Socket createSocket(int port) throws IOException {
        return new Socket(getServerAddress(), port);
    }

    private ObjectOutputStream createOutputStream(Socket socket) throws IOException {
        return new ObjectOutputStream(socket.getOutputStream());
    }

    private ObjectInputStream createInputStream(Socket socket) throws IOException {
        return new ObjectInputStream(socket.getInputStream());
    }

    private Envelope sendReceive(Envelope envelope, int port) throws IOException, ClassNotFoundException {
        Socket socket = createSocket(port);
        socket.setSoTimeout(4000);
        createOutputStream(socket).writeObject(envelope);
        return (Envelope) createInputStream(socket).readObject();
    }

    private void sendReplays(Envelope envelope, int n_replays){
        try {
            int i = 0;
            while(i < n_replays){
                Socket socket = createSocket(PORT);
                createOutputStream(socket).writeObject(envelope);
                i++;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


//////////////////////////
//						//
//	Handshake Methods	//
//						//
//////////////////////////

    private byte[] askForServerNonce(PublicKey key, int port) throws NonceTimeoutException {
        try {
             return sendReceive(new Envelope(new Request("NONCE", key)), port).getResponse().getNonce();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
            throw new NonceTimeoutException("The operation was not possible, please try again!"); //IOException apanha tudo
        }
        return new byte[0];
    }

    private void startHandshake(PublicKey publicKey, int port) throws NonceTimeoutException {
        setServerNonce(port, askForServerNonce(publicKey, port));
        setClientNonce(port, criptoManager.generateClientNonce());
    }

    private boolean checkNonce(Response response, int port){
        if(Arrays.equals(response.getNonce(), getClientNonce(port))) {
            setClientNonce(port, null);
            setServerNonce(port, null);
            return true;
        }
        setClientNonce(port, null);
        setServerNonce(port, null);
        return false;
    }

    
 //////////////////////////////////////////////////////////////
 //															 //
 //   Methods that check if Responses must throw exceptions  //
 //															 //
 //////////////////////////////////////////////////////////////
    
    public void checkRegister(Response response) throws AlreadyRegisteredException, UnknownPublicKeyException {
        if(!response.getSuccess()){
            int error = response.getErrorCode();
            if(error == -7) {
                throw new UnknownPublicKeyException("Such key doesn't exist in the server side!");
            }
            else if(error == -2) {
                throw new AlreadyRegisteredException("User with that public key already registered in the DPAS!");
            }
        }
    }

    public void checkPost(Response response) throws UserNotRegisteredException,
            MessageTooBigException, InvalidAnnouncementException {
        if(!response.getSuccess()){
            int error = response.getErrorCode();
            if(error == -1) {
                throw new UserNotRegisteredException("This user is not registered!");
            }
            else if(error == -4) {
                throw new MessageTooBigException("Message cannot exceed 255 characters!");
            }
            else if(error == -5) {
                throw new InvalidAnnouncementException("Announcements referenced do not exist!");
            }
        }
    }

    public void checkRead(Response response) throws UserNotRegisteredException, InvalidPostsNumberException, TooMuchAnnouncementsException {
        if(!response.getSuccess()){
            int error = response.getErrorCode();
            if(error == -1) {
                throw new UserNotRegisteredException("This user is not registered!");
            }
            else if(error == -3) {
                throw new UserNotRegisteredException("The user you're reading from is not registered!");
            }
            else if(error == -6) {
                throw new InvalidPostsNumberException("Invalid announcements number to be read!");
            }
            else if(error == -10) {
                throw new TooMuchAnnouncementsException("The number of announcements you've asked for exceeds the number of announcements existing in such board");
            }
        }
    }
    
    public void checkReadGeneral(Response response) throws InvalidPostsNumberException, TooMuchAnnouncementsException, UserNotRegisteredException {
        if(!response.getSuccess()){
            int error = response.getErrorCode();
            if(error == -1) {
                throw new UserNotRegisteredException("This user is not registered!");
            }
            else if(error == -6) {
                throw new InvalidPostsNumberException("Invalid announcements number to be read!");
            }
            else if(error == -10) {
                throw new TooMuchAnnouncementsException("The number of announcements you've asked for exceeds the number of announcements existing in such board");
            }
		}
	}

    
 ///////////////////////
 //					  //
 //   API Functions   //
 //	  	     		  //
 ///////////////////////
    
	//////////////////////////////////////////////////
	//				     REGISTER  					//
	//////////////////////////////////////////////////
    public int register() throws AlreadyRegisteredException, UnknownPublicKeyException, NonceTimeoutException, OperationTimeoutException, FreshnessException, IntegrityException{
        int i = 0;
        int port = PORT;
        System.out.println(getnFaults()*3 + 1);
        while (i < getnFaults()*3 + 1){
            System.out.println(port);
            registerMethod(port++);
            i++;
        }
        return 1;
    }

    public int registerMethod(int port) throws AlreadyRegisteredException, UnknownPublicKeyException, NonceTimeoutException, OperationTimeoutException, FreshnessException, IntegrityException {

        startHandshake(getPublicKey(), port);

        Request request = new Request("REGISTER", getPublicKey(), getServerNonce(port), getClientNonce(port));

        Envelope envelopeRequest = new Envelope(request, criptoManager.cipherRequest(request, getPrivateKey()));
        
        /***** SIMULATE ATTACKER: changing the userX key to userY pubKey [in this case user3] *****/
        if(isIntegrityFlag()) {
        	envelopeRequest.getRequest().setPublicKey(criptoManager.getPublicKeyFromKs(userName, "user3"));
        }
        /******************************************************************************************/
        try {
            Envelope envelopeResponse = sendReceive(envelopeRequest, port);
            /***** SIMULATE ATTACKER: send replayed messages to the server *****/
            if(isReplayFlag()){
                sendReplays(envelopeRequest, 2);
            }
            /********************************************************************/
            if(!checkNonce(envelopeResponse.getResponse(), port)) {
                throw new FreshnessException(registerErrorMessage);
            }
            if(!criptoManager.checkHash(envelopeResponse, userName)) {
                throw new IntegrityException(registerErrorMessage);
            }
            checkRegister(envelopeResponse.getResponse());
            if(envelopeResponse.getResponse().getSuccess()){
                // On success, return 1
                return 1;
            }
            else{
                return 0;
            }
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            return 0;
        } catch(IOException e){
            throw new OperationTimeoutException("There was a problem in the connection we cannot infer precisely if the register was successful. Please try to log in");
        }
    }

    //////////////////////////////////////////////////
    //					   POST  					//
    //////////////////////////////////////////////////



    public int postAux(PublicKey key, String message, int[] announcs, boolean isGeneral, byte[] serverNonce, byte[] clientNonce, PrivateKey privateKey, int port) throws InvalidAnnouncementException,
                                                                                                                                                                       UserNotRegisteredException, MessageTooBigException, OperationTimeoutException, FreshnessException, IntegrityException {
        Request request;
        if(isGeneral){
            request = new Request("POSTGENERAL", key, message, announcs, serverNonce, clientNonce);
        }
        else{
            request = new Request("POST", key, message, announcs, serverNonce, clientNonce);
        }

        Envelope envelopeRequest = new Envelope(request, criptoManager.cipherRequest(request, privateKey));

        /***** SIMULATE ATTACKER: changing the message (tamper) *****/
        if(isIntegrityFlag()) {
        	envelopeRequest.getRequest().setMessage("Olá, eu odeio-te");
        }
        /************************************************************/
        try {

            Envelope envelopeResponse = sendReceive(envelopeRequest, port);
            /***** SIMULATE ATTACKER: replay register *****/
            if(isReplayFlag()){
                sendReplays(envelopeRequest, 2);
            }
            /**********************************************/
            if(!checkNonce(envelopeResponse.getResponse(), port)){
                throw new FreshnessException(errorMessage);
            }
            if(!criptoManager.checkHash(envelopeResponse, userName)){
                throw new IntegrityException(errorMessage);
            }
            checkPost(envelopeResponse.getResponse());
            // On success, return 1
            return 1;
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
            throw new OperationTimeoutException("There was a problem in the connection, please do a read operation to confirm your post!");
        }
        return 0;
    }

    public int post(String message, int[] announcs, boolean isGeneral) throws UserNotRegisteredException, MessageTooBigException, InvalidAnnouncementException, NonceTimeoutException, OperationTimeoutException, FreshnessException, IntegrityException {
        int responses = 0;
        int counter = 0;
        int port = PORT;

        int[] results = new int[(getnFaults() * 3 + 1) / 2 + 1];

        CompletableFuture<Integer>[] tasks = new CompletableFuture[getnFaults() * 3 + 1];

        for (int i = 0; i < tasks.length; i++) {

            int finalPort = port;

            tasks[i] = CompletableFuture.supplyAsync(() -> {
                try {
                    return postMethod(message, announcs, isGeneral, finalPort);
                } catch (MessageTooBigException e) {
                    return -4;
                } catch (UserNotRegisteredException e) {
                    return -1;
                } catch (InvalidAnnouncementException e) {
                    return -5;
                } catch (NonceTimeoutException e) {
                    return -11;
                } catch (OperationTimeoutException e) {
                    return -12;
                } catch (FreshnessException e) {
                    return -13;
                } catch (IntegrityException e) {
                    return -14;
                }
            });
            port++;
        }
        System.out.println((getnFaults() * 3 + 1) / 2);
        while (responses < (getnFaults() * 3 + 1) / 2) {

            for (int i = 0; i < tasks.length; i++) {

                if (tasks[i].isDone()) {
                    System.out.println("is done");

                    responses++;

                    try {
                        results[counter++] = tasks[i].get().intValue();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } catch (ExecutionException e) {
                        e.printStackTrace();
                    }
                    if (responses == (getnFaults() * 3 + 1) / 2 + 1)
                        break;
                }
                if (i == tasks.length - 1)
                    i = 0;
            }
        }

        switch (getQuorum(results)) {

            case (-1):
                throw new UserNotRegisteredException("User not Registered");
            case (-4):
                throw new MessageTooBigException("Message Too Big");
            case (-5):
                throw new InvalidAnnouncementException("Invalid announcement");
            case (-11):
                throw new NonceTimeoutException("Nonce timeout");
            case (-12):
                throw new OperationTimeoutException("Operation timeout");
            case (-13):
                throw new FreshnessException("Freshness Exception");
            case (-14):
                throw new IntegrityException("Integrity Exception");

        }


        // gonna use first position but later verify if all equal
        System.out.println("RESULTS: " + results[0] + '\n' + results[1] + '\n' + results[2]);
        return results[0];

    }

    public int getQuorum(int[] results){
        int final_result = results[0];
        for(int i = 1; i < results.length; i++){
            if(results[i] == final_result){
                continue;
            }
            else {
                System.out.println("Not quorum n sei o que fazer");
            }
        }
        return final_result;
    }

    public int postMethod(String message, int[] announcs, boolean isGeneral, int port) throws MessageTooBigException, UserNotRegisteredException, InvalidAnnouncementException, NonceTimeoutException, OperationTimeoutException, FreshnessException, IntegrityException {
        startHandshake(getPublicKey(), port);
        return postAux(getPublicKey(), message, announcs, isGeneral, getServerNonce(port), getClientNonce(port), getPrivateKey(), port);
    }
    
    /*public int postGeneral(String message, int[] announcs) throws MessageTooBigException, UserNotRegisteredException, InvalidAnnouncementException, NonceTimeoutException, OperationTimeoutException, FreshnessException, IntegrityException {
        startHandshake(getPublicKey());
        return postAux(getPublicKey(), message, announcs, true, getServerNonce(), getClientNonce(), getPrivateKey());
    }*/

    //////////////////////////////////////////////////
    //				      READ						//
    //////////////////////////////////////////////////

    public JSONObject read(String announcUserName, int number) throws InvalidPostsNumberException, UserNotRegisteredException, TooMuchAnnouncementsException, NonceTimeoutException, OperationTimeoutException, FreshnessException, IntegrityException {

        startHandshake(getPublicKey(), PORT);
        
        PublicKey pubKeyToReadFrom = criptoManager.getPublicKeyFromKs(userName, announcUserName);

    	Request request = new Request("READ", getPublicKey(), pubKeyToReadFrom, number, getServerNonce(PORT), getClientNonce(PORT));

        Envelope envelopeRequest = new Envelope(request, criptoManager.cipherRequest(request, getPrivateKey()));
        
        /***** SIMULATE ATTACKER: changing the user to read from. User might think is going to read from user X but reads from Y [in this case user3] (tamper) *****/
        if(isIntegrityFlag()) {
        	envelopeRequest.getRequest().setPublicKeyToReadFrom(criptoManager.getPublicKeyFromKs(userName, "user3"));
        }
        /**********************************************************************************************************************************************************/

        try {
            Envelope envelopeResponse = sendReceive(envelopeRequest, PORT);
            /***** SIMULATE ATTACKER: send replayed messages to the server *****/
            if(isReplayFlag()){
                sendReplays(envelopeRequest, 2);
            }
            /*******************************************************************/
            if (!checkNonce(envelopeResponse.getResponse(), PORT)) {
                throw new FreshnessException(errorMessage);
            }
            if (!criptoManager.checkHash(envelopeResponse, userName)) {
                throw new IntegrityException(errorMessage);
            }
            checkRead(envelopeResponse.getResponse());
            return envelopeResponse.getResponse().getJsonObject();
            
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
        	throw new OperationTimeoutException("There was a problem with the connection, please try again!");
        } 
        return null;
    }

    public JSONObject readGeneral(int number) throws InvalidPostsNumberException, TooMuchAnnouncementsException, IntegrityException, OperationTimeoutException, NonceTimeoutException, UserNotRegisteredException, FreshnessException {

    	startHandshake(getPublicKey(), PORT);

    	Request request = new Request("READGENERAL", getPublicKey(), number, getServerNonce(PORT), getClientNonce(PORT));
    	
    	Envelope envelopeRequest = new Envelope(request, criptoManager.cipherRequest(request, getPrivateKey()));

        try {
            Envelope envelopeResponse = sendReceive(envelopeRequest, PORT);
            /***** SIMULATE ATTACKER: send replayed messages to the server *****/
            if(isReplayFlag()){
                sendReplays(new Envelope(request, null), 2);
            }
            /*******************************************************************/
            if (!checkNonce(envelopeResponse.getResponse(), PORT)) {
                throw new FreshnessException(errorMessage);
            }
            if(!criptoManager.checkHash(envelopeResponse, userName)){
                throw new IntegrityException("There was a problem with your request, we cannot infer if you registered. Please try to login");
            }
			checkReadGeneral(envelopeResponse.getResponse());
            return envelopeResponse.getResponse().getJsonObject();

        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
        	throw new OperationTimeoutException("There was a problem with the connection, please try again!");
        }
        return null;
    }    
}
