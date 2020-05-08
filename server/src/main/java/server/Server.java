package server;

import exceptions.IntegrityException;
import exceptions.NonceTimeoutException;
import library.Envelope;
import library.Request;
import library.Response;

import org.apache.commons.io.FileUtils;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.FileReader;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class Server implements Runnable {
	
	
    private ServerSocket server;
    private String serverPort;
    private ConcurrentHashMap<PublicKey, String> userIdMap = null;
	private ConcurrentHashMap<PublicKey, byte[]> serverNonces = null;
    private AtomicInteger totalAnnouncements;
    private CryptoManager cryptoManager = null;

    // File path strings
    private String storagePath = "";
    private String userMapPath = "";
    private String userMapPathCopy = "";
    private String totalAnnouncementsPath = "";
    private String totalAnnouncementsPathCopy = "";
    private String announcementBoardsPath = "";
    private String generalBoardPath = "";

    /********** Simulated Attacks Variables ***********/
    
    private boolean replayFlag = false;
    private boolean dropNonceFlag = false;
    private boolean dropOperationFlag = false;
    private boolean handshake = false;
    private boolean integrityFlag = false;
    private Response oldResponse;
    private Envelope oldEnvelope;
    
    /**************************************************/

    
    /***************** Atomic Register variables ******************/
    
    private HashMap<String, Pair<Integer, AnnouncementBoard>> usersBoards = null;
    private ConcurrentHashMap<String, ConcurrentHashMap<String, Pair<Integer, Integer>>> listening = null;
    
    /**************************************************************/


    protected Server(ServerSocket ss, int port) {

        server            = ss;
        serverPort		  = port + "";  //adding "" converts int to string

        cryptoManager = new CryptoManager(port);
        serverNonces = new ConcurrentHashMap<>();
        oldResponse   = new Response(cryptoManager.generateRandomNonce());
        oldEnvelope   = new Envelope(oldResponse, null);
        
        // Path variables
        storagePath       		   = "./storage/port_" + serverPort + "/";
        userMapPath       		   = storagePath + "UserIdMap.ser";
        userMapPathCopy			   = storagePath + "UserIdMap_copy.ser";
        totalAnnouncementsPath	   = storagePath + "TotalAnnouncements.ser";
        totalAnnouncementsPathCopy = storagePath + "TotalAnnouncements_copy.ser";
        announcementBoardsPath	   = storagePath + "announcementboards/";
        generalBoardPath		   = storagePath + "generalboard/";
        File gb = new File(generalBoardPath);
        gb.mkdirs();

        listening = new ConcurrentHashMap<>();
        
        System.out.println("SERVER ON PORT " + this.serverPort + ": Up and running.");
        getUserIdMap();
        getTotalAnnouncementsFromFile();
       
        initUsersBoard();

        newListener();
    }
    
    
//////////////////////////////////////////
//  								    //
//         Main method running          //
//    									//
//////////////////////////////////////////
    @SuppressWarnings("all")
    public void run(){

        Socket socket = null;
        ObjectOutputStream outStream;
        ObjectInputStream inStream;

        try{
            socket = server.accept();
        } catch (IOException e) {
            e.printStackTrace();
        }

        newListener();

        try {
            inStream = new ObjectInputStream(socket.getInputStream());
            outStream = new ObjectOutputStream(socket.getOutputStream());
            try {
                System.out.println("SERVER ON PORT " + this.serverPort + ": User connected.");
                Envelope envelope = (Envelope) inStream.readObject();
                switch(envelope.getRequest().getOperation()) {
                    case "REGISTER":
                        if(checkExceptions(envelope.getRequest(), outStream, new int[] {-7}) && 
                            cryptoManager.verifyRequest(envelope.getRequest(), envelope.getSignature(), envelope.getRequest().getUsername()) &&
                            cryptoManager.checkNonce(envelope.getRequest().getPublicKey(), envelope.getRequest().getServerNonce()) &&
                            checkExceptions(envelope.getRequest(), outStream, new int[] {-2}))
                            {
                            register(envelope.getRequest(), outStream);
                        }
                        break;
                    case "POST":
                        if(checkExceptions(envelope.getRequest(), outStream, new int[] {-7}) &&
                        	cryptoManager.verifyRequest(envelope.getRequest(), envelope.getSignature(), userIdMap.get(envelope.getRequest().getPublicKey())) &&
                            cryptoManager.checkNonce(envelope.getRequest().getPublicKey(), envelope.getRequest().getServerNonce()) &&
                            checkExceptions(envelope.getRequest(), outStream, new int[] {-1, -4, -5})) 
                            {
                            write(envelope.getRequest(), outStream);
                        }
                        break;
                    case "POSTGENERAL":
                        if(checkExceptions(envelope.getRequest(), outStream, new int[] {-7}) && 
                        	cryptoManager.verifyRequest(envelope.getRequest(), envelope.getSignature(), userIdMap.get(envelope.getRequest().getPublicKey())) &&
                            cryptoManager.checkNonce(envelope.getRequest().getPublicKey(), envelope.getRequest().getServerNonce()) &&
                            checkExceptions(envelope.getRequest(), outStream, new int[] {-1, -4, -5}))
                            {
                            writeGeneral(envelope.getRequest(), outStream);
                        }
                        break;
                    case "READ":
                        if(checkExceptions(envelope.getRequest(), outStream, new int[] {-7}) && 
                        	cryptoManager.verifyRequest(envelope.getRequest(), envelope.getSignature(), userIdMap.get(envelope.getRequest().getPublicKey())) &&
                            cryptoManager.checkNonce(envelope.getRequest().getPublicKey(), envelope.getRequest().getServerNonce()) &&
                            checkExceptions(envelope.getRequest(), outStream, new int[] {-3, -6, -10}))
                            {
                            read(envelope.getRequest(), outStream);
                        }
                        break;
                    case "READGENERAL":
                        if(checkExceptions(envelope.getRequest(), outStream, new int[] {-7}) &&
                        	cryptoManager.verifyRequest(envelope.getRequest(), envelope.getSignature(), userIdMap.get(envelope.getRequest().getPublicKey())) &&
                            cryptoManager.checkNonce(envelope.getRequest().getPublicKey(), envelope.getRequest().getServerNonce()) &&
                            checkExceptions(envelope.getRequest(), outStream, new int[] {-6, -10}))
                        	{
                            readGeneral(envelope.getRequest(), outStream);
                        }
                        break;
                    case "READCOMPLETE":
                        if(checkExceptions(envelope.getRequest(), outStream, new int[] {-7}) &&
                            cryptoManager.verifyRequest(envelope.getRequest(), envelope.getSignature(), userIdMap.get(envelope.getRequest().getPublicKey())) &&
                            cryptoManager.checkNonce(envelope.getRequest().getPublicKey(), envelope.getRequest().getServerNonce()) &&
                            checkExceptions(envelope.getRequest(), outStream, new int[] {-3}))
                            {
                            readComplete(envelope.getRequest());
                        }
                    case "NONCE":
                        handshake = true;
                        byte[] randomNonce = cryptoManager.generateRandomNonce(envelope.getRequest().getPublicKey());
                        if(!dropNonceFlag) {
                        	send(new Response(randomNonce), outStream);
                        } else {
                        	System.out.println("SERVER ON PORT " + this.serverPort + ": DROPPED NONCE");
                        }
                        handshake = false;
                        break;
                    case "WTS":
                        System.out.println("WTS METHOD");
                        if(checkExceptions(envelope.getRequest(), outStream, new int[] {-7}) &&
                        	cryptoManager.verifyRequest(envelope.getRequest(), envelope.getSignature(), userIdMap.get(envelope.getRequest().getPublicKey())) &&
                            cryptoManager.checkNonce(envelope.getRequest().getPublicKey(), envelope.getRequest().getServerNonce()) &&
                            checkExceptions(envelope.getRequest(), outStream, new int[] {-1}))
                        	{
                                System.out.println("entrei no wts");
                            wtsRequest(envelope.getRequest(), outStream);
                        }
                    	break;
                    case "DELETEALL":
                        deleteUsers();
                        break;
                    case "SHUTDOWN":
                        shutDown();
                        break;
                    case "REPLAY_FLAG_TRUE":
                        replayFlag = true;
                        break;
                    case "REPLAY_FLAG_FALSE":
                        replayFlag = false;
                        break;
                    case "INTEGRITY_FLAG_TRUE":
                        integrityFlag = true;
                        break;
                    case "INTEGRITY_FLAG_FALSE":
                        integrityFlag = false;
                        break;
                    case "DROP_NONCE_FLAG_TRUE":
                    	dropNonceFlag = true;
                    	break;
                    case "DROP_NONCE_FLAG_FALSE":
                    	dropNonceFlag = false;
                    	break;
                    case "DROP_OPERATION_FLAG_TRUE":
                    	dropOperationFlag = true;
                    	break;
                    case "DROP_OPERATION_FLAG_FALSE":
                    	dropOperationFlag = false;
                    	break;
                    default:
                        break;
                }
                socket.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }catch (Exception e) {
            e.printStackTrace();
        }
    }

    
//////////////////////////////////////////
//  									//
//             API Methods              //
//	                                    //
//////////////////////////////////////////

    //////////////////////////////////////////////////
    //				    REGISTER					//
    //////////////////////////////////////////////////
    
    public void register(Request request, ObjectOutputStream outStream) {
        System.out.println("REGISTER METHOD");
        String username = cryptoManager.checkKey(request.getPublicKey());
        String path = announcementBoardsPath + username;
        File file = new File(path);
        file.mkdirs();
        userIdMap.put(request.getPublicKey(), username);
        saveUserIdMap();
        usersBoards.put(request.getUsername(), new Pair<>(0, new AnnouncementBoard(request.getUsername())));

        if(!dropOperationFlag) {
            System.out.println(Base64.getEncoder().encodeToString(request.getClientNonce()));
            send(new Response(true, request.getClientNonce(), cryptoManager.getPublicKeyFromKs("server")), outStream);
        } else {
            System.out.println("DROPPED REGISTER");
        }
    }

    
    //////////////////////////////////////////////////
    //				      POST						//
    //////////////////////////////////////////////////
    @SuppressWarnings("unchecked")
	private void write(Request request, ObjectOutputStream outStream) throws IntegrityException, NonceTimeoutException {
        // Get userName from keystore
        System.out.println(request.getTs());
        System.out.println(usersBoards.get(userIdMap.get(request.getPublicKey())).getFirst());
        if(request.getTs() > usersBoards.get(userIdMap.get(request.getPublicKey())).getFirst()) {  // if ts' > ts then (ts, val) := (ts', v')
            System.out.println("time stamps sao superiores");
            usersBoards.get(userIdMap.get(request.getPublicKey())).setFirst(request.getTs());  // ts = ts'

            String username = userIdMap.get(request.getPublicKey());                    //val = v'
            String path = announcementBoardsPath + username + "/";
            // Write to file
            JSONObject announcementObject =  new JSONObject();
            announcementObject.put("id", Integer.toString(getTotalAnnouncements()));
            announcementObject.put("user", username);
            announcementObject.put("message", request.getMessage());

            Date dNow = new Date();
            SimpleDateFormat ft = new SimpleDateFormat ("dd-MM-yyyy 'at' HH:mm");
            announcementObject.put("date", ft.format(dNow).toString());

            int[] refAnnouncements = request.getAnnouncements();

            if(refAnnouncements != null){
                JSONArray annoucementsList = new JSONArray();
                for(int i = 0; i < refAnnouncements.length; i++){
                    annoucementsList.add(Integer.toString(refAnnouncements[i]));
                }
                announcementObject.put("ref_announcements", annoucementsList);
            }

            try {
                System.out.println("gonna save");
                saveFile(path + Integer.toString(getTotalAnnouncements()), announcementObject.toJSONString()); //GeneralBoard
                System.out.println("just saved");
                usersBoards.get(userIdMap.get(request.getPublicKey())).getSecond().addAnnouncement(announcementObject); //update val with the new post
            } catch (IOException e) {
                send(new Response(false, -9, request.getClientNonce()), outStream);
            }

            incrementTotalAnnouncs();
            saveTotalAnnouncements();

        }
        if(listening.contains(userIdMap.get(request.getPublicKey()))){ // no one is reading from who is writing
            System.out.println("alguem ta a ler");
            for(Map.Entry<String, Pair<Integer, Integer>> entry : listening.get(userIdMap.get(request.getPublicKey())).entrySet()){  //for every listening[q]
                byte[] nonce = null;
                try {
                    nonce = startOneWayHandshake(entry.getKey());
                } catch (NonceTimeoutException e) {
                    e.printStackTrace();
                } catch (IntegrityException e) {
                    e.printStackTrace();
                }
                // -----> One way Handshake
                int port = getClientPort(entry.getKey());
                try(ObjectOutputStream outputStream = new ObjectOutputStream(new Socket("localhost", port).getOutputStream())) {
                    int rid = entry.getValue().getFirst();
                    int number = entry.getValue().getSecond();
                    int ts = usersBoards.get(entry.getKey()).getFirst();
                    JSONObject objectToSend = usersBoards.get(entry.getKey()).getSecond().getAnnouncements(number);

                    send(new Request("VALUE", true, rid, ts, nonce, objectToSend, Integer.parseInt(serverPort), cryptoManager.getPublicKeyFromKs("server")), outputStream);

                } catch (IOException e) {
                    e.printStackTrace();
                }


                //send
            }
        }
        System.out.println("ninguem ta a ler");

        if(!dropOperationFlag) {
            send(new Response(true, request.getClientNonce(), usersBoards.get(userIdMap.get(request.getPublicKey())).getFirst(), cryptoManager.getPublicKeyFromKs("server")), outStream);
        } else {
            System.out.println("SERVER ON PORT " + this.serverPort + ": DROPPED POST");
        }

    }

    
    //////////////////////////////////////////////////
    //				   POST GENERAL		            //
    //////////////////////////////////////////////////
    @SuppressWarnings("unchecked")
	private void writeGeneral(Request request, ObjectOutputStream outStream) {
        // Get userName from keystore
        String username = userIdMap.get(request.getPublicKey());
        String path = announcementBoardsPath + username + "/";
        // Write to file
        JSONObject announcementObject =  new JSONObject();
        announcementObject.put("id", Integer.toString(getTotalAnnouncements()));
        announcementObject.put("user", username);
        announcementObject.put("message", request.getMessage());

        Date dNow = new Date();
        SimpleDateFormat ft = new SimpleDateFormat ("dd-MM-yyyy 'at' HH:mm");
        announcementObject.put("date", ft.format(dNow).toString());

        int[] refAnnouncements = request.getAnnouncements();

        if(refAnnouncements != null){
            JSONArray annoucementsList = new JSONArray();
            for(int i = 0; i < refAnnouncements.length; i++){
                annoucementsList.add(Integer.toString(refAnnouncements[i]));
            }
            announcementObject.put("ref_announcements", annoucementsList);
        }

        path = generalBoardPath;

        try {
            saveFile(path + Integer.toString(getTotalAnnouncements()), announcementObject.toJSONString()); //GeneralBoard
        } catch (IOException e) {
            send(new Response(false, -9, request.getClientNonce()), outStream);
        }

        incrementTotalAnnouncs();
        saveTotalAnnouncements();
        if(!dropOperationFlag) {

            send(new Response(true, request.getClientNonce()), outStream);
        } else {
            System.out.println("SERVER ON PORT " + this.serverPort + ": DROPPED POST");
        }
    }
    
    
    //////////////////////////////////////////////////
    //				      READ						//
    //////////////////////////////////////////////////
    @SuppressWarnings("unchecked")
	private void read(Request request, ObjectOutputStream outStream) {

        if(listening.contains(userIdMap.get(request.getPublicKeyToReadFrom()))){  //someone is already reading that board
            listening.get(userIdMap.get(request.getPublicKeyToReadFrom())).put(userIdMap.get(request.getPublicKey()), new Pair<Integer, Integer>(request.getRid(), request.getNumber()));  //listening [p] := r ;
        }
        else{
            listening.put(userIdMap.get(request.getPublicKeyToReadFrom()), new ConcurrentHashMap<>()); //listening [p] := r ;
        }

        String[] directoryList = getDirectoryList(request.getPublicKeyToReadFrom());
        int directorySize = directoryList.length;

        System.out.println("SERVER ON PORT " + this.serverPort + ": READ method");
        String username = userIdMap.get(request.getPublicKeyToReadFrom());
        String path = announcementBoardsPath + username + "/";

        int total;
        if(request.getNumber() == 0) { //all posts
            total = directorySize;
        } else {
            total = request.getNumber();
        }

        Arrays.sort(directoryList);
        JSONParser parser = new JSONParser();
        try(ObjectOutputStream outputStream = new ObjectOutputStream(new Socket("localhost", getClientPort(userIdMap.get(request.getPublicKey()))).getOutputStream())){
            JSONArray annoucementsList = new JSONArray();
            JSONObject announcement;

            String fileToRead;
            for (int i=0; i<total; i++) {
                fileToRead = directoryList[directorySize-1];
                announcement = (JSONObject) parser.parse(new FileReader(path + fileToRead));
                directorySize--;
                annoucementsList.add(announcement);
            }
            JSONObject announcementsToSend =  new JSONObject();
            announcementsToSend.put("announcementList", annoucementsList);

            // if(!dropOperationFlag) {
            //     // -----> Handshake one way
            //     //send(new Response(true, announcementsToSend, request.getNonceClient(), request.getRid()), outStream);
            // } else {
            // 	System.out.println("DROPPED READ");
            // } 

            // Send response to client
            // ------> Handshake one way
            byte[] nonce = startOneWayHandshake(userIdMap.get(request.getPublicKey()));

            send(new Request("VALUE", true, request.getRid(), usersBoards.get(userIdMap.get(request.getPublicKeyToReadFrom())).getFirst(), nonce, announcementsToSend, Integer.parseInt(serverPort), cryptoManager.getPublicKeyFromKs("server")), outputStream);

        } catch(Exception e) {
            e.printStackTrace();
            send(new Response(false, -8, request.getClientNonce()), outStream);
        }
    }
    

    //////////////////////////////////////////////////
    //				   READ GENERAL				    //
    //////////////////////////////////////////////////
    @SuppressWarnings("unchecked")
	private void readGeneral(Request request, ObjectOutputStream outStream) {

        String[] directoryList = getDirectoryList(request.getPublicKeyToReadFrom());
        int directorySize = directoryList.length;

        String path = "";
        System.out.println("SERVER ON PORT " + this.serverPort + ": READGENERAL method");
        path = generalBoardPath;

        int total;
        if(request.getNumber() == 0) { //all posts
            total = directorySize;
        } else {
            total = request.getNumber();
        }

        Arrays.sort(directoryList);
        JSONParser parser = new JSONParser();
        try{
            JSONArray annoucementsList = new JSONArray();
            JSONObject announcement;

            String fileToRead;
            for (int i=0; i<total; i++) {
                fileToRead = directoryList[directorySize-1];
                announcement = (JSONObject) parser.parse(new FileReader(path + fileToRead));
                directorySize--;
                annoucementsList.add(announcement);
            }
            JSONObject announcementsToSend =  new JSONObject();
            announcementsToSend.put("announcementList", annoucementsList);
            if(!dropOperationFlag) {
                // send(new Response(true, announcementsToSend, request.getNonceClient()), outStream);   FIXME-> enviar o rid?
            } else {
                System.out.println("SERVER ON PORT " + this.serverPort + ": DROPPED READ GENERAL");
            }
        } catch(Exception e) {
            send(new Response(false, -8, request.getClientNonce()), outStream);
        }
    }


    private void readComplete(Request request){
        if(request.getRid() == listening.get(userIdMap.get(request.getPublicKeyToReadFrom())).get(userIdMap.get(request.getPublicKey())).getFirst()){
            listening.get(userIdMap.get(request.getPublicKeyToReadFrom())).remove(userIdMap.get(request.getPublicKey()));
        }
    }
    
    
    //////////////////////////////////////////////
    //				   SEND WTS				    //
    //////////////////////////////////////////////
    
    private void wtsRequest(Request request, ObjectOutputStream outStream) {
    	int wts = usersBoards.get(userIdMap.get(request.getPublicKey())).getFirst();
        System.out.println("wts: " + wts);
        System.out.println(request.getClientNonce());
    	send(new Response(true, request.getClientNonce(), wts, cryptoManager.getPublicKeyFromKs("server")), outStream);
    }
    
    
//////////////////////////////////////////
//										//
//           Auxiliary Methods          //
//    									//
//////////////////////////////////////////

    private Boolean checkValidAnnouncements(int[] announcs){
        int total = getTotalAnnouncements();
        for (int i = 0; i < announcs.length; i++) { 		      
            if (announcs[i] >= total ) {
                return false;
            }		
        } 	
        return true;
    }
    
    private String[] getDirectoryList(PublicKey key){
        String path = "";
        if(key == null) {
            path = generalBoardPath;
        }
        else {
            path = announcementBoardsPath + userIdMap.get(key) + "/";
        }

        File file = new File(path);
        return file.list();
    }

    private void send(Response response, ObjectOutputStream outputStream){
        try {
        	// Sign response
            byte[] signature = cryptoManager.signResponse(response);
            /***** SIMULATE ATTACKER: changing an attribute from the response will make it different from the hash] *****/
            if(integrityFlag) {
                response.setSuccess(false);
                response.setErrorCode(-33);
            }
            /************************************************************************************************************/
            /***** SIMULATE ATTACKER: Replay attack by sending a replayed message from the past (this message is simulated)] *****/
            if(replayFlag && !handshake){
                outputStream.writeObject(oldEnvelope);
            }
            /*********************************************************************************************************************/
            else{
                outputStream.writeObject(new Envelope(response, signature));
            }
        } catch (IOException e) {
            e.printStackTrace();
        } 
    }

    private void send(Request request, ObjectOutputStream outputStream){
        try {
            // Sign response
            byte[] signature = cryptoManager.signRequest(request);
            /***** SIMULATE ATTACKER: changing an attribute from the response will make it different from the hash] *****/
            if(integrityFlag) {
                //request.setSuccess(false);  --> alteramos outras coisas
                //request.setErrorCode(-33);
            }
            /************************************************************************************************************/
            /***** SIMULATE ATTACKER: Replay attack by sending a replayed message from the past (this message is simulated)] *****/
            if(replayFlag && !handshake){
                outputStream.writeObject(oldEnvelope);
            }
            /*********************************************************************************************************************/
            else{
                outputStream.writeObject(new Envelope(request, signature));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Envelope sendReceive(Request serverRequest, ObjectOutputStream outputStream, ObjectInputStream inputStream) {
        Envelope envelope = null;
        try {
        	// Sign request
            byte[] signature = cryptoManager.signRequest(serverRequest);
            outputStream.writeObject(new Envelope(serverRequest, signature));
            // exceptions de timeout e o crl (nonce timeout)   FIXME -> falta adicionar as exceptions
            return (Envelope) inputStream.readObject();
        } catch (IOException | 
                ClassNotFoundException e) {
            e.printStackTrace();
        }
        return envelope; 
    }


    private void saveFile(String completePath, String announcement) throws IOException {
        byte[] bytesToStore = announcement.getBytes();
        File file = new File(completePath);

        try(FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(bytesToStore);

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
    }
    
    private void newListener() {
        (new Thread(this)).start();
    }
    
    
////////////////////////////////////////////////////////////////////////////////
//   									                                      //
//  Method used to delete Tests' populate && Shut down Server && Start server //
//										                                      //
////////////////////////////////////////////////////////////////////////////////

    public void deleteUsers() throws IOException {

        System.out.println("SERVER ON PORT " + this.serverPort + ": DELETE OPERATION");

        userIdMap.clear();
        saveUserIdMap();

        String path = announcementBoardsPath;

        FileUtils.deleteDirectory(new File(path));
        File files = new File(path);
        files.mkdirs();

        path = generalBoardPath;

        FileUtils.deleteDirectory(new File(path));
        files = new File(path);
        files.mkdirs();

        setTotalAnnouncements(0);
        saveTotalAnnouncements();
    }

    private void shutDown(){
    	System.out.println("SERVER ON PORT " + this.serverPort + ": SHUTDOWN OPERATION");
        String name = ManagementFactory.getRuntimeMXBean().getName();
        System.out.println(name.split("@")[0]);

        try {
            Runtime.getRuntime().exec("kill -SIGINT " + name.split("@")[0]);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    
/////////////////////////////////////////////
//										   //
//     Method to initialize user pairs     //
//										   //
/////////////////////////////////////////////
    @SuppressWarnings("unchecked")
	private void initUsersBoard(){

        usersBoards = new HashMap<String, Pair<Integer, AnnouncementBoard>>();

        String[] users = new File(announcementBoardsPath).list();
        if(users != null){
            for(String user : users){
                String path = announcementBoardsPath + '/' + user;
                String[] postsFromUser = new File(path).list();
                try{

                    JSONParser parser = new JSONParser();
                    JSONArray annoucementsList = new JSONArray();

                    for (String file : postsFromUser) {
                        annoucementsList.add((JSONObject) parser.parse(new FileReader(path + '/' + file)));
                    }

                    int timestamp = postsFromUser.length;
                    AnnouncementBoard ab = new AnnouncementBoard(user, annoucementsList);
                    Pair<Integer, AnnouncementBoard> pair = new Pair<Integer, AnnouncementBoard>(timestamp, ab);
                    usersBoards.put(user, pair);

                } catch(Exception e) {
                    System.out.println("OPAAAA");
                }
            }
        }
    }
    
    
/////////////////////////////////////////////
//										   //
// Methods to save/get userIdMap from File //
//										   //
/////////////////////////////////////////////
    
    private void saveUserIdMap() {
        try(ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(userMapPathCopy))) {
            out.writeObject(userIdMap);
            System.out.println("SERVER ON PORT " + this.serverPort + ": Created updated copy of the userIdMap");

            File original = new File(userMapPath);
            File copy = new File(userMapPathCopy);

            if(original.delete()){
                copy.renameTo(original);
            }

         } catch (IOException i) {
            i.printStackTrace();
         }
    }
    
    @SuppressWarnings("unchecked")
	private void getUserIdMap() {
        try(ObjectInputStream in = new ObjectInputStream(new FileInputStream(userMapPath))) {
           userIdMap = (ConcurrentHashMap<PublicKey, String>) in.readObject();
        } catch (ClassNotFoundException c) {
           System.out.println("SERVER ON PORT " + this.serverPort + ": Map<PublicKey, String> class not found");
           c.printStackTrace();
           return;
        }
        catch(FileNotFoundException e){
            userIdMap = new ConcurrentHashMap<PublicKey, String>();
            createOriginalUserMap();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void createOriginalUserMap() {

        try(ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(userMapPath))) {
            out.writeObject(userIdMap);

        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    
/////////////////////////////////////////////////////////
//										               //
// Methods to get/update total announcements from File //
//										               //
/////////////////////////////////////////////////////////


    private void incrementTotalAnnouncs(){
        totalAnnouncements.incrementAndGet();
    }

    private void setTotalAnnouncements(int value){
        totalAnnouncements.set(value);
    }

    private int getTotalAnnouncements(){
        return totalAnnouncements.get();
    }

    private void saveTotalAnnouncements(){
        try(ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(totalAnnouncementsPathCopy))) {
            out.writeObject(totalAnnouncements.get());
            System.out.println("SERVER ON PORT " + this.serverPort + ": Serialized data saved in copy");

            File original = new File(totalAnnouncementsPath);
            File copy = new File(totalAnnouncementsPathCopy);

            if(original.delete()){
                copy.renameTo(original);
            }

        } catch (IOException i) {
            i.printStackTrace();
        }
    }

    private void getTotalAnnouncementsFromFile() {
        try(ObjectInputStream in = new ObjectInputStream(new FileInputStream(totalAnnouncementsPath))) {
           int a = (int) in.readObject();
           totalAnnouncements = new AtomicInteger(a);
        }
        catch(FileNotFoundException e){
            totalAnnouncements = new AtomicInteger(0);
            createOriginalAnnouncs();
        } catch (
            IOException | 
            ClassNotFoundException e) {
            e.printStackTrace();
        }
        System.out.println("SERVER ON PORT " + this.serverPort + ": Total announcements-> " + totalAnnouncements);
    }

    private void createOriginalAnnouncs(){

        try(ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(totalAnnouncementsPath))) {
            out.writeObject(totalAnnouncements.get());
        } catch (IOException i) {
            i.printStackTrace();
        }
    }

    //////////////////////////////////////////
    //  									//
    //          Check exceptions            //
    //	                                    //
    //////////////////////////////////////////
    @SuppressWarnings("all")
    public boolean checkExceptions(Request request, ObjectOutputStream outStream, int[] codes) {
        for (int i = 0; i < codes.length; i++) {
            switch(codes[i]) {
                // ## UserNotRegistered ## -> check if user is registed
                case -1:
                    if(!userIdMap.containsKey(request.getPublicKey())) {
                        send(new Response(false, -1, request.getClientNonce()), outStream);
                        return false;
                    }
                    break;
                // ## AlreadyRegistered ## -> check if user is already registered
                case -2:
                    if (userIdMap.containsKey(request.getPublicKey())) {
                        send(new Response(false, -2, request.getClientNonce()), outStream);
                        return false;
                    }
                    break;
                // ## UserNotRegistered ## -> [READ] check if user TO READ FROM is registered
                case -3:
                    if(!userIdMap.containsKey(request.getPublicKeyToReadFrom())) {
                        send(new Response(false, -3, request.getClientNonce()), outStream);
                        return false;
                    }
                    break;
                // ## MessageTooBig ## -> Check if message length exceeds 255 characters
                case -4:
                    if(request.getMessage().length() > 255) {
                        send(new Response(false, -4, request.getClientNonce()), outStream);
                        return false;
                    }
                    break;
                // ## InvalidAnnouncement ## -> checks if announcements refered by the user are valid
                case -5:
                    if(request.getAnnouncements() != null && !checkValidAnnouncements(request.getAnnouncements())) {
                        send(new Response(false, -5, request.getClientNonce()), outStream);
                        return false;      
                    }
                    break;
                // ## InvalidPostsNumber ## -> check if number of requested posts are bigger than zero
                case -6:
                    if (request.getNumber() < 0) {
                        send(new Response(false, -6, request.getClientNonce()), outStream);
                        return false;
                    }
                    break;
                // ## UnknownPublicKey ## -> Check if key is null or known by the Server. If method is read, check if key to ready from is null
                case -7:
                    if (request.getPublicKey() == null || cryptoManager.checkKey(request.getPublicKey()) == "" || (request.getOperation().equals("READ") && (request.getPublicKeyToReadFrom() == null || cryptoManager.checkKey(request.getPublicKeyToReadFrom()) == ""))) {
                        send(new Response(false, -7, request.getClientNonce()), outStream);
                        return false;
                    }
                    break;
                // ## TooMuchAnnouncements ## -> Check if user is trying to read more announcements that Board number of announcements
                case -10:
                    if ((request.getOperation().equals("READ") && request.getNumber() > getDirectoryList(request.getPublicKey()).length) || (request.getOperation().equals("READGENERAL") && request.getNumber() > getDirectoryList(null).length) ) {
                        send(new Response(false, -10, request.getClientNonce()), outStream);
                        return false;
                    }
                    break;

                default:
                    break;
            }
        }
        return true;
    }


    public void setClientNonce(PublicKey clientKey, byte[] clientNonce) {
    	this.serverNonces.put(clientKey, clientNonce);
    }

    public byte[] getClientNonce (PublicKey clientKey) {
        return serverNonces.get(clientKey);
    }

    private int getClientPort(String client){
        try(BufferedReader reader = new BufferedReader(new FileReader("../clients_addresses.txt"))){
            String line;
            while((line = reader.readLine()) != null){
                String[] splitted = line.split(":");
                if(splitted[0].equals(client)){
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


    private byte[] startOneWayHandshake(String username) throws NonceTimeoutException, IntegrityException {
        Envelope nonceEnvelope = askForClientNonce(cryptoManager.getPublicKeyFromKs("server"), getClientPort(username));
        System.out.println("consegui enviar e tenho o nonce: " + nonceEnvelope.getResponse().getNonce());
        if(cryptoManager.verifyResponse(nonceEnvelope.getResponse(), nonceEnvelope.getSignature(), userIdMap.get(nonceEnvelope.getResponse().getPublicKey()))) {
            return nonceEnvelope.getResponse().getNonce();
        } else {
            throw new IntegrityException("Integrity Exception");
        }
    }

    private Envelope askForClientNonce(PublicKey serverKey, int port) throws NonceTimeoutException {
        try(Socket socket = new Socket("localhost", port);
            ObjectOutputStream outputStream = new ObjectOutputStream(socket.getOutputStream());
            ObjectInputStream inputStream = new ObjectInputStream(socket.getInputStream())) {
            return sendReceive(new Request("NONCE", serverKey), outputStream, inputStream);
        } catch (IOException e) {
            throw new NonceTimeoutException("The operation was not possible, please try again!"); //IOException apanha tudo
        }
    }
}
