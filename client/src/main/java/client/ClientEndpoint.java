package client;

import exceptions.*;
import library.Envelope;
import library.Request;
import library.Response;

import org.json.simple.JSONObject;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class ClientEndpoint {

	private Map<PublicKey, Integer> serversPorts = null;
    private String serverAddress = null;

    private PublicKey publicKey  = null;
    private String username = null;
    private CryptoManager cryptoManager = null;

    /********** Atomic Register Variables ************/
    int wts = -1; // -1 means we must ask server for the current wts
    int rid = 0;

    /************ Replication variables *************/
    private static final int PORT = 9000;
    private int nServers = 4;
    private int nFaults  = 1;
    private int nQuorum  = 2;
 

    private String registerErrorMessage = "There was a problem with your request, we cannot infer if you registered. Please try to login.";
    private String errorMessage = "There was a problem with your request. Please try again.";


    private ReplayAttacker replayAttacker = null;
    private boolean replayFlag = false;
    private boolean integrityFlag = false;
    
    
//////////////////////////////////////////
//										//
//	 Constructor, Getters and Setters	//
//										//
//////////////////////////////////////////

    public ClientEndpoint(String username) {
    	cryptoManager = new CryptoManager(username);
        setPublicKey(cryptoManager.getPublicKeyFromKs(username));
        this.username = username;
        serversPorts = initiateServersPorts();
    }

    public boolean isReplayFlag() {
        return replayFlag;
    }

    public void setReplayFlag(boolean replayFlag) {
        this.replayFlag = replayFlag;
        if(replayFlag)
        	this.replayAttacker = new ReplayAttacker(this.serverAddress);
    }

    public boolean isIntegrityFlag() {
		return integrityFlag;
	}

	public void setIntegrityFlag(boolean integrityFlag) {
		this.integrityFlag = integrityFlag;
	}

    public PublicKey getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(PublicKey publicKey) {
        this.publicKey = publicKey;
    }

    
//////////////////////////////////////////
//										//
//	Communication with Server methods	//
//										//
//////////////////////////////////////////
    
    private Socket createSocket(int port) throws IOException {
        return new Socket(serverAddress, port);
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
        // Sign envelope
        envelope.setSignature(cryptoManager.signRequest(envelope.getRequest()));
        createOutputStream(socket).writeObject(envelope);
        Envelope responseEnvelope = (Envelope) createInputStream(socket).readObject();
        return responseEnvelope;
    }

    private void send(Envelope envelope, int port) throws IOException, ClassNotFoundException {
        Socket socket = createSocket(port);
        socket.setSoTimeout(4000);
        ObjectOutputStream out = createOutputStream(socket);
        // Sign envelope
        envelope.setSignature(cryptoManager.signRequest(envelope.getRequest()));
        out.writeObject(envelope);
        out.close();
    }


//////////////////////////
//						//
//	Handshake Methods	//
//						//
//////////////////////////

    private Envelope askForServerNonce(PublicKey clientKey, int port) throws NonceTimeoutException {
        try {
        	return sendReceive(new Envelope(new Request("NONCE", clientKey)), port);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            throw new NonceTimeoutException(ExceptionsMessages.OPERATION_NOT_POSSIBLE); //IOException apanha tudo
        }
        return null;
    }

    private byte[] startHandshake(PublicKey serverKey, boolean oneWay) throws NonceTimeoutException, IntegrityException {
    	Envelope nonceEnvelope = askForServerNonce(getPublicKey(), serversPorts.get(serverKey));
    	if(cryptoManager.verifyResponse(nonceEnvelope.getResponse(), nonceEnvelope.getSignature(), serverKey)) {
    		if(!oneWay) {
                cryptoManager.generateRandomNonce(serverKey);
    		}
            return nonceEnvelope.getResponse().getNonce();
        } else {
    		throw new IntegrityException(ExceptionsMessages.OPERATION_NOT_POSSIBLE);
        }
    }

    
///////////////////////
//					 //
//   API Functions   //
//	  	     		 //
///////////////////////
    
	//////////////////////////////////////////////////
	//				     REGISTER  					//
	//////////////////////////////////////////////////
    
    public int register() throws AlreadyRegisteredException, UnknownPublicKeyException, NonceTimeoutException, OperationTimeoutException, FreshnessException, IntegrityException {
        // Variables to store responses and their results
        int[] results = new int[nServers];
        // Threads that will make the requests to the server
        Thread[] tasks = new Thread[nServers];
        // Register to all servers
        for (PublicKey serverKey : serversPorts.keySet()) {
        	tasks[serversPorts.get(serverKey) - PORT] = new Thread(new Runnable() {
                public void run() {
                	try {
                		results[serversPorts.get(serverKey) - PORT] = registerMethod(serverKey);
    				} catch (AlreadyRegisteredException e) {
    					results[serversPorts.get(serverKey) - PORT] = -2;
    				} catch (UnknownPublicKeyException e) {
    					results[serversPorts.get(serverKey) - PORT] = -7;
    				} catch (FreshnessException e) {
    					results[serversPorts.get(serverKey) - PORT] = -13;
                	} catch (NonceTimeoutException e) {
                		results[serversPorts.get(serverKey) - PORT] = -11;
    				} catch (IntegrityException e) {
    					results[serversPorts.get(serverKey) - PORT] = -14;
    				} catch (OperationTimeoutException e) {
    					results[serversPorts.get(serverKey) - PORT] = -12;
    				}
                }
            });
        	tasks[serversPorts.get(serverKey) - PORT].start();
        }
        // FIXME está a espera que todas as threads acabem!!!
        boolean stillAlive = true;
        while(stillAlive) {
        	stillAlive = false;
            for (PublicKey serverKey : serversPorts.keySet()) {
            	if(tasks[serversPorts.get(serverKey) - PORT].isAlive()) {
            		stillAlive = true;
            		break;
            	}
            }
        }
        // Get Quorum from the result to make a decision regarding the responses
        int result = getMajorityOfQuorumInt(results);
        switch (result) {
            case (-2):
                throw new AlreadyRegisteredException(ExceptionsMessages.ALREADY_REGISTERED);
            case (-11):
                throw new NonceTimeoutException(ExceptionsMessages.OPERATION_NOT_POSSIBLE);
            case (-12):
                throw new OperationTimeoutException(ExceptionsMessages.CANT_INFER_REGISTER);
            case (-13):
                throw new FreshnessException(ExceptionsMessages.CANT_INFER_REGISTER);
            case (-14):
                throw new IntegrityException(ExceptionsMessages.CANT_INFER_REGISTER);
            default:
            	return result;
        }
        
    }

    public int registerMethod(PublicKey serverKey) throws AlreadyRegisteredException, UnknownPublicKeyException, NonceTimeoutException, OperationTimeoutException, FreshnessException, IntegrityException {
    	// Ask server for a nonce and set a client nonce to send
        byte[] serverNonce = startHandshake(serverKey, false);
        // Create request and seal it up inside envelope
        Request request = new Request("REGISTER", getPublicKey(), serverNonce, cryptoManager.getNonce(serverKey), username);

        Envelope envelopeRequest = new Envelope(request);
        /***** SIMULATE ATTACKER: changing the userX key to userY pubKey [in this case user3] *****/
        if(isIntegrityFlag()) {
        	envelopeRequest.getRequest().setPublicKey(cryptoManager.getPublicKeyFromKs("user3"));
        }
        /******************************************************************************************/
        try {
            Envelope envelopeResponse = sendReceive(envelopeRequest, serversPorts.get(serverKey));
            /***** SIMULATE ATTACKER: send replayed messages to the server *****/
            if(isReplayFlag()){
                this.replayAttacker.sendReplays(envelopeRequest, 2);
            }
            /*******************************************************************/
            // Verify freshness of message (by checking if the request contains a fresh nonce)
            if(!cryptoManager.checkNonce(envelopeResponse.getResponse().getPublicKey(), envelopeResponse.getResponse().getNonce())) {
                throw new FreshnessException(registerErrorMessage);
            }
            // Verify message integrity
            if(!cryptoManager.verifyResponse(envelopeResponse.getResponse(), envelopeResponse.getSignature(), serverKey)) {
                throw new IntegrityException(registerErrorMessage);
            }
            // Verify if response has exceptions
            ResponseChecker.checkRegister(envelopeResponse.getResponse());
            // On success, return 1
            if(envelopeResponse.getResponse().getSuccess()) {
                return 1;
            }
            else{
                return 0;
            }
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            return 0;
        } catch(IOException e) {
            throw new OperationTimeoutException(ExceptionsMessages.CANT_INFER_REGISTER);
        }
    }

    
    //////////////////////////////////////////////////
    //					   POST  					//
    //////////////////////////////////////////////////
    
    public int post(String message, int[] announcs, boolean isGeneral) throws UserNotRegisteredException, MessageTooBigException, InvalidAnnouncementException, NonceTimeoutException, OperationTimeoutException, FreshnessException, IntegrityException {
        // Ask Servers for actual wts in case we don't have it in memory
        if(wts == -1) {
        	wts = askForWts();
        }
        wts = wts + 1;  
        // Variables to store responses and their results
        int[] results = new int[nServers];
        // Threads that will make the requests to the server
        Thread[] tasks = new Thread[nServers];
        // Post to all servers
        for (PublicKey serverKey : serversPorts.keySet()) {
        	tasks[serversPorts.get(serverKey) - PORT] = new Thread(new Runnable() {
                public void run() {
                	try {
                		results[serversPorts.get(serverKey) - PORT] = postMethod(message, announcs, isGeneral, serverKey, wts);
                	} catch (UserNotRegisteredException e) {
                		results[serversPorts.get(serverKey) - PORT] = -1;              		
    				} catch (InvalidAnnouncementException e) {
                		results[serversPorts.get(serverKey) - PORT] = -5;              		
    				} catch (MessageTooBigException e) {
    					results[serversPorts.get(serverKey) - PORT] = -4;
    				} catch (FreshnessException e) {
    					results[serversPorts.get(serverKey) - PORT] = -13;
                	} catch (NonceTimeoutException e) {
                		results[serversPorts.get(serverKey) - PORT] = -11;
    				} catch (IntegrityException e) {
    					results[serversPorts.get(serverKey) - PORT] = -14;
    				} catch (OperationTimeoutException e) {
    					results[serversPorts.get(serverKey) - PORT] = -12;
    				}
                }
            });
        	tasks[serversPorts.get(serverKey) - PORT].start();
        }
        // FIXME está a espera que todas as threads acabem!!!
        boolean stillAlive = true;
        while(stillAlive) {
        	stillAlive = false;
            for (PublicKey serverKey : serversPorts.keySet()) {
            	if(tasks[serversPorts.get(serverKey) - PORT].isAlive()) {
            		stillAlive = true;
            		break;
            	}
            }
        }
        // Get Quorum from the result to make a decision regarding the responses
        int result = getQuorumInt(results);
        switch (result) {
            case (-1):
                throw new UserNotRegisteredException(ExceptionsMessages.USER_NOT_REGISTERED);
            case (-4):
                throw new MessageTooBigException(ExceptionsMessages.MESSAGE_TOO_BIG);
            case (-5):
                throw new InvalidAnnouncementException(ExceptionsMessages.INVALID_ANNOUNCEMENTS);
            case (-11):
                throw new NonceTimeoutException(ExceptionsMessages.OPERATION_NOT_POSSIBLE);
            case (-12):
                throw new OperationTimeoutException(ExceptionsMessages.CANT_INFER_POST);
            case (-13):
                throw new FreshnessException(ExceptionsMessages.CANT_INFER_POST);
            case (-14):
                throw new IntegrityException(ExceptionsMessages.CANT_INFER_POST);
        }
        return result;
    }

    public int postMethod(String message, int[] announcs, boolean isGeneral, PublicKey serverKey, int ts) throws MessageTooBigException, UserNotRegisteredException, InvalidAnnouncementException, NonceTimeoutException, OperationTimeoutException, FreshnessException, IntegrityException {
        byte[] serverNonce = startHandshake(serverKey, false);
        return write(getPublicKey(), message, announcs, isGeneral, serverNonce, cryptoManager.getNonce(serverKey), serverKey, ts);
    }

	public int write(PublicKey clientKey, String message, int[] announcs, boolean isGeneral, byte[] serverNonce, byte[] clientNonce, PublicKey serverKey, int ts) throws InvalidAnnouncementException,
                                                                                                                                                                       UserNotRegisteredException, MessageTooBigException, OperationTimeoutException, FreshnessException, IntegrityException {
        Request request;
        if(isGeneral){
            request = new Request("POSTGENERAL", clientKey, message, announcs, serverNonce, clientNonce, ts);
        }
        else{
            request = new Request("POST", clientKey, message, announcs, serverNonce, clientNonce, ts);
        }

        Envelope envelopeRequest = new Envelope(request);

        /***** SIMULATE ATTACKER: changing the message (tamper) *****/
        if(isIntegrityFlag()) {
        	envelopeRequest.getRequest().setMessage("Olá, eu odeio-te");
        }
        /************************************************************/
        try {

            Envelope envelopeResponse = sendReceive(envelopeRequest, serversPorts.get(serverKey));

            /***** SIMULATE ATTACKER: replay register *****/
            if(isReplayFlag()){
                this.replayAttacker.sendReplays(envelopeRequest, 2);
            }
            /**********************************************/
            // Verify freshness of message (by checking if the request contains a fresh nonce)
            if(!cryptoManager.checkNonce(envelopeResponse.getResponse().getPublicKey(), envelopeResponse.getResponse().getNonce())){
                throw new FreshnessException(errorMessage);
            }
            // Verify message integrity
            if(!cryptoManager.verifyResponse(envelopeResponse.getResponse(), envelopeResponse.getSignature(), serverKey)){
                throw new IntegrityException(errorMessage);
            }
            ResponseChecker.checkPost(envelopeResponse.getResponse());
            // On success, return 1
            return 1;
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            throw new OperationTimeoutException(ExceptionsMessages.CANT_INFER_POST);
        }
        return 0;
    }


    //////////////////////////////////////////////////
    //				      READ						//
    //////////////////////////////////////////////////

    public JSONObject read(String announcUserName, int number) throws UserNotRegisteredException, InvalidPostsNumberException, TooMuchAnnouncementsException, NonceTimeoutException, OperationTimeoutException, FreshnessException, IntegrityException {
        rid += 1;
        //forall t > 0 do answers [t] := [⊥] N ;
        Listener listener = null;
        ServerSocket listenerSocket = null;
        try {
            listenerSocket = new ServerSocket(getClientPort());
            listener = new Listener(listenerSocket, nQuorum, username, getPublicKey());
        } catch (IOException e) {
            e.printStackTrace();
        }
        // Threads that will make the requests to the server
        Thread[] tasksRead = new Thread[nServers];
        // Send read to all servers
        for (PublicKey serverKey : serversPorts.keySet()) {
        	tasksRead[serversPorts.get(serverKey) - PORT] = new Thread(new Runnable() {
                public void run() {
                	readMethod(announcUserName, number, serverKey, rid);
                }
            });
        	tasksRead[serversPorts.get(serverKey) - PORT].start();
        }
        // Wait for listeners to get result
        while(listener.getResult() == null) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        
        try {
            listenerSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        Request result = listener.getResult();
        // Threads that will make the requests to the server
        Thread[] tasksReadComplete = new Thread[nServers];
        // Send 'read complete' to all servers
        for (PublicKey serverKey : serversPorts.keySet()) {
        	tasksReadComplete[serversPorts.get(serverKey) - PORT] = new Thread(new Runnable() {
                public void run() {
                	readComplete(announcUserName, serverKey, rid);
                }
            });
        	tasksReadComplete[serversPorts.get(serverKey) - PORT].start();
        }
        // FIXME está a espera que todas as threads acabem!!!
        // Kill remaining read threads
        boolean stillAlive = true;
        while(stillAlive) {
        	stillAlive = false;
            for (PublicKey serverKey : serversPorts.keySet()) {
            	if(tasksReadComplete[serversPorts.get(serverKey) - PORT].isAlive()) {
            		stillAlive = true;
            		break;
            	}
            }
        }
        // Kill remaining read complete threads
        stillAlive = true;
        while(stillAlive) {
        	stillAlive = false;
            for (PublicKey serverKey : serversPorts.keySet()) {
            	if(tasksReadComplete[serversPorts.get(serverKey) - PORT].isAlive()) {
            		stillAlive = true;
            		break;
            	}
            }
        }
        /*if(result.getSuccess()){
            return result.getJsonObject();
        }
        else{
            switch (result.getErrorCode()) {
                case (-1):
                    throw new UserNotRegisteredException("User not Registered");
                case (-3):
                    throw new UserNotRegisteredException("The user you're reading from is not registered!");  //OLD EXCEPTION FIX ME
                case (-6):
                    throw new InvalidPostsNumberException("Invalid announcements number to be read!");
                case (-10):
                    throw new TooMuchAnnouncementsException("The number of announcements you've asked for exceeds the number of announcements existing in such board");
                case (-11):
                    throw new NonceTimeoutException("Nonce timeout");
                case (-12):
                    throw new OperationTimeoutException("Operation timeout");
                case (-13):
                    throw new FreshnessException("Freshness Exception");
                case (-14):
                    throw new IntegrityException("Integrity Exception");
                default:
                    break;

            }
        }*/
        return result.getJsonObject();
    }

    public void readMethod(String announcUserName, int number, PublicKey serverKey, int rid) {
        try {
			//  -----> Handshake one way
			byte[] serverNonce = startHandshake(serverKey, true);
			//  -----> get public key to read from
			PublicKey pubKeyToReadFrom = cryptoManager.getPublicKeyFromKs(announcUserName);
			//  -----> send read operation to server
			Request request = new Request("READ", getPublicKey(), pubKeyToReadFrom, number, serverNonce, rid);
			Envelope envelopeRequest = new Envelope(request, cryptoManager.signRequest(request));
			send(envelopeRequest, serversPorts.get(serverKey));
		} catch (ClassNotFoundException |
				NonceTimeoutException   |
				IntegrityException 	 	|
				IOException e) {
			e.printStackTrace();
		}
    }

    private void readComplete(String announcUserName, PublicKey serverKey, int rid){

        try {
            //  -----> Handshake one way
        byte[] serverNonce = startHandshake(serverKey, true);

        //  -----> get public key to read from
            PublicKey pubKeyToReadFrom = cryptoManager.getPublicKeyFromKs(announcUserName);

            //  -----> send read complete operation to server
            Request request = new Request("READCOMPLETE", getPublicKey(), pubKeyToReadFrom, serverNonce, rid);
            Envelope envelopeRequest = new Envelope(request, cryptoManager.signRequest(request));
            send(envelopeRequest, serversPorts.get(serverKey));

        } catch (ClassNotFoundException |
                NonceTimeoutException   |
                IOException             |
                IntegrityException   e) {
            e.printStackTrace();
            //Impossible to know if fault from the server when doing handshake or drop attack
            //throw new OperationTimeoutException("There was a problem in the connection, please do a read operation to confirm your post!");
        }
    }

    
	//////////////////////////////////////////////////
	//				   READ GENERAL					//
	//////////////////////////////////////////////////
    
    public JSONObject readGeneral(int number) throws UserNotRegisteredException, InvalidPostsNumberException, TooMuchAnnouncementsException, NonceTimeoutException, OperationTimeoutException, FreshnessException, IntegrityException {
        int responses = 0;
        int counter = 0;
        int port = PORT;

        Response[] results = new Response[(nFaults * 3 + 1) / 2 + 1];

        CompletableFuture<Response>[] tasks = new CompletableFuture[nFaults * 3 + 1];

        for (int i = 0; i < tasks.length; i++) {

            int finalPort = port;

            tasks[i] = CompletableFuture.supplyAsync(() -> readGeneralMethod(number, finalPort));
            port++;
        }
        while (responses < nFaults * 3 + 1 / 2) {
            for (int i = 0; i < tasks.length; i++) {

                if (tasks[i].isDone()) {

                    responses++;

                    try {
                        results[counter++] = tasks[i].get();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } catch (ExecutionException e) {
                        e.printStackTrace();
                    }
                    if (responses > nQuorum)
                        break;
                }
                if (i == tasks.length - 1)
                    i = 0;
            }
        }
        Response result = getQuorumResponse(results);
        if(result.getSuccess()){
            return result.getJsonObject();
        }
        else{
            switch (result.getErrorCode()) {
                case (-1):
                    throw new UserNotRegisteredException("This user is not registered!");
                case (-6):
                    throw new InvalidPostsNumberException("Announcements referenced do not exist!");
                case (-10):
                    throw new TooMuchAnnouncementsException("The number of announcements you've asked for exceeds the number of announcements existing in such board");
                case (-11):
                    throw new NonceTimeoutException("The operation was not possible, please try again!");
                case (-12):
                    throw new OperationTimeoutException("Operation timeout");
                case (-13):
                    throw new FreshnessException("Freshness Exception");
                case (-14):
                    throw new IntegrityException("Integrity Exception");

                default:
                    break;

            }
        }
        return new JSONObject();
    }

    public Response readGeneralMethod(int number, int port) {

        /*try {
            startHandshake(port);
        } catch (NonceTimeoutException e) {
            return new Response(false, -11, null);
        }

        Request request = new Request("READGENERAL", getPublicKey(), number, getServerNonce(publicKey), getClientNonce(port));
    	
    	Envelope envelopeRequest = new Envelope(request);

        try {
            Envelope envelopeResponse = sendReceive(envelopeRequest, port);
            /***** SIMULATE ATTACKER: send replayed messages to the server *****/
        /*
        if(isReplayFlag()){
            	this.replayAttacker.sendReplays(new Envelope(request, null), 2);
            }
            /*******************************************************************/
        /*
        if (!checkNonce(envelopeResponse.getResponse(), port)) {
                return new Response(false, -13, null);
                //throw new FreshnessException(errorMessage);
            }
            if(!cryptoManager.verifyResponse(envelopeResponse.getResponse(), envelopeResponse.getSignature(), userName)){
                return new Response(false, -14, null);
                //throw new IntegrityException("There was a problem with your request, we cannot infer if you registered. Please try to login");
            }
			//checkReadGeneral(envelopeResponse.getResponse());
            return envelopeResponse.getResponse();

        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            return new Response(false, -12, null);
        	//throw new OperationTimeoutException("There was a problem with the connection, please try again!");
        }
        */return null;
    }
    
	//////////////////////////////////////////////////
	//				   ASK FOR WTS					//
	//////////////////////////////////////////////////
    
    private int askForWts() throws NonceTimeoutException, IntegrityException, OperationTimeoutException, FreshnessException, UserNotRegisteredException {
        // Variables to store responses and their results
        int[] results = new int[nServers];
        // Threads that will make the requests to the server
        Thread[] tasks = new Thread[nServers];
        // Register to all servers
        for (PublicKey serverKey : serversPorts.keySet()) {
        	tasks[serversPorts.get(serverKey) - PORT] = new Thread(new Runnable() {
                public void run() {
                	try {
                		results[serversPorts.get(serverKey) - PORT] = askForSingleWts(serverKey);
                	} catch (UserNotRegisteredException e) {
                		results[serversPorts.get(serverKey) - PORT] = -1;
                	} catch (FreshnessException e) {
    					results[serversPorts.get(serverKey) - PORT] = -13;
                	} catch (NonceTimeoutException e) {
                		results[serversPorts.get(serverKey) - PORT] = -11;
    				} catch (IntegrityException e) {
    					results[serversPorts.get(serverKey) - PORT] = -14;
    				} catch (OperationTimeoutException e) {
    					results[serversPorts.get(serverKey) - PORT] = -12;
    				}
                }
            });
        	tasks[serversPorts.get(serverKey) - PORT].start();
        }
        // FIXME está a espera que todas as threads acabem!!!
        boolean stillAlive = true;
        while(stillAlive) {
        	stillAlive = false;
            for (PublicKey serverKey : serversPorts.keySet()) {
            	if(tasks[serversPorts.get(serverKey) - PORT].isAlive()) {
            		stillAlive = true;
            		break;
            	}
            }
        }
        // Get Quorum from the result to make a decision regarding the responses
        int result = getMajorityOfQuorumInt(results);
        switch (result) {
            case (-1):
                throw new UserNotRegisteredException(ExceptionsMessages.USER_NOT_REGISTERED);
            case (-11):
                throw new NonceTimeoutException(ExceptionsMessages.OPERATION_NOT_POSSIBLE);
            case (-12):
                throw new OperationTimeoutException(ExceptionsMessages.OPERATION_NOT_POSSIBLE);
            case (-13):
                throw new FreshnessException(ExceptionsMessages.OPERATION_NOT_POSSIBLE);
            case (-14):
                throw new IntegrityException(ExceptionsMessages.OPERATION_NOT_POSSIBLE);
            default:
            	return result;
        }
        
	}

    
	private int askForSingleWts(PublicKey serverKey) throws NonceTimeoutException, IntegrityException, OperationTimeoutException, FreshnessException, UserNotRegisteredException {
		// Make handshake with server

		byte[] serverNonce = startHandshake(serverKey, false);
        // Make wts Request sign it and send inside envelope
        Request request = new Request("WTS", getPublicKey(), serverNonce, cryptoManager.getNonce(serverKey));
    	Envelope envelopeRequest = new Envelope(request, cryptoManager.signRequest(request));

    	// Get wts inside a Response
    	int singleWts = -666;
		try {
			Envelope envelopeResponse = sendReceive(envelopeRequest, serversPorts.get(serverKey));
			// Verify Response's Freshness
            if(!cryptoManager.checkNonce(envelopeResponse.getResponse().getPublicKey(), envelopeResponse.getResponse().getNonce())){
                throw new FreshnessException(errorMessage);
            }
	    	// Verify Response's Integrity
	        if(!cryptoManager.verifyResponse(envelopeResponse.getResponse(), envelopeResponse.getSignature(), serverKey)) {
	            throw new IntegrityException("EPA NAO SEI AINDA O QUE ESCREVER AQUI MAS UM ATACANTE ALTEROU A RESP DO WTS");
	        } else {
	        	singleWts = envelopeResponse.getResponse().getTs();
	        }
	        ResponseChecker.checkAskWts(envelopeResponse.getResponse());
	        return singleWts;
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			throw new OperationTimeoutException(ExceptionsMessages.OPERATION_NOT_POSSIBLE);
		}
        return singleWts;
	}


//////////////////////////////////////////////////
//				 Auxiliary Methods				//
//////////////////////////////////////////////////

    private int getQuorumInt(int[] results) {
        HashMap<Integer, Integer> map = new HashMap<>();
        for(int i = 0; i < results.length; i++) {
            if (!map.containsKey(results[i])) {
                map.put(results[i], 1);
            } else {
                map.put(results[i], map.get(results[i]) + 1);
                if (map.get(results[i]) > nQuorum) {
                    return results[i];
                }
            }
        }
        //NOT QUORUM
        return 0;
    }

    private Response getQuorumResponse(Response[] results) {
        Response finalResult = results[0];
        for(int i = 1; i < results.length; i++) {
            if (results[i].getSuccess() == finalResult.getSuccess() && results[i].getErrorCode() == finalResult.getErrorCode()) {
                continue;
            } else {
                System.out.println("Not quorum n sei o que fazer");
            }
        }
        return finalResult;
    }
    
    private int getMajorityOfQuorumInt(int[] results) {
        HashMap<Integer, Integer> map = new HashMap<>();
        for(int i = 0; i < results.length; i++) {
            if (!map.containsKey(results[i])) {
                map.put(results[i], 1);
            } else {
                map.put(results[i], map.get(results[i]) + 1);
                if (map.get(results[i]) > (results.length)/2) {
                    return results[i];
                }
            }
        }
        // REPLICAS HAVE MORE THAN F FAULTS
        return -666;
    }
    
    private int getClientPort(){
        try(BufferedReader reader = new BufferedReader(new FileReader("../clients_addresses.txt"))){
            String line;
            while((line = reader.readLine()) != null){
                String[] splitted = line.split(":");
                if(splitted[0].equals(username)){
                    return Integer.parseInt(splitted[2]);
                }
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return 0;
    }

    private HashMap<PublicKey, Integer> initiateServersPorts(){
        return cryptoManager.initiateServersPorts(nServers);

    }
    

}
