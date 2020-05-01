package Server;

import java.io.*;
import java.net.ServerSocket;

public class App 
{
    public static void main( String[] args ) {

		try {
			ServerSocket ss = new ServerSocket(9000);
			Server new Server(ss);
		} catch (IOException e) {
			e.printStackTrace();
		}

	}

}
