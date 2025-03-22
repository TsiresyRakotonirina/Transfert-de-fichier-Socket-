import java.io.*;
import java.net.*;
import java.util.*;

public class Server {
    private static int PORT;
    // private static final int PORT = 5000;
    // private static final String[] SUB_SERVERS = {"localhost:5001", "localhost:5002", "localhost:5003"};
    private static String[] SUB_SERVERS = new String[3];
    private static final Set<String> fileList = new HashSet<>(); // Liste des fichiers principaux
    private static String SERVER_ADDRESS;
    private static int SUB_SERVER_PORT;
    private static String STORAGE_DIR;

    public static void main(String[] args) {
        // Lire le fichier de configuration
        loadConfig();
        try (ServerSocket serverSocket = new ServerSocket(PORT,50,InetAddress.getByName(SERVER_ADDRESS))) {
            System.out.println("Serveur principal en attente de connexions sur le port " + PORT);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Nouveau client connecté : " + clientSocket.getInetAddress());

                // Lancer un thread pour gérer ce client
                new Thread(() -> handleClient(clientSocket)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void loadConfig() {
        try (BufferedReader reader = new BufferedReader(new FileReader("config.txt"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("SUB_SERVERS1")) {
                    SUB_SERVERS[0] = line.split("=")[1].trim();
                } else if (line.startsWith("SUB_SERVERS2")) {
                    SUB_SERVERS[1] = line.split("=")[1].trim();
                } else if (line.startsWith("SUB_SERVERS3")) {
                    SUB_SERVERS[2] = line.split("=")[1].trim();
                } else if (line.startsWith("PORT")) {
                    PORT = Integer.parseInt(line.split("=")[1].trim());
                } else if (line.startsWith("SERVER_ADDRESS")) {
                    SERVER_ADDRESS = line.split("=")[1].trim();
                } else if (line.startsWith("SUB_SERVER_PORT")) {
                    SUB_SERVER_PORT = Integer.parseInt(line.split("=")[1].trim());
                } else if (line.startsWith("STORAGE_DIR")) {
                    STORAGE_DIR = line.split("=")[1].trim();
                    // Créer le répertoire s'il n'existe pas
                    new File(STORAGE_DIR).mkdirs();
                }
            }
        } catch (IOException e) {
            System.err.println("Erreur lors du chargement du fichier de configuration : " + e.getMessage());
        }
    }

    private static void handleClient(Socket clientSocket) {
        try (DataInputStream dis = new DataInputStream(clientSocket.getInputStream());
             DataOutputStream dos = new DataOutputStream(clientSocket.getOutputStream())) {

            String command = dis.readUTF();

            switch (command) {
                case "LIST":
                    handleListCommand(dos);
                    break;
                case "SEND":
                    handleSendCommand(clientSocket, dis);
                    break;
                case "RECEIVE":
                    handleReceiveCommand(clientSocket, dis, dos);
                    break;
                case "DELETE":
                    handleDeleteFile(dis, dos);
                    break;
                default:
                    System.out.println("Commande non reconnue : " + command);
                    break;
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static void handleListCommand(DataOutputStream dos) throws IOException {
        // Envoyer la liste des fichiers principaux au client
        dos.writeInt(fileList.size());
        for (String fileName : fileList) {
            dos.writeUTF(fileName);
        }
        System.out.println("Liste des fichiers envoyée au client.");
    }

    private static void handleSendCommand(Socket clientSocket, DataInputStream dis) throws IOException {
        String fileName = dis.readUTF();
        long fileSize = dis.readLong();

        // Ajouter le fichier à la liste des fichiers principaux
        fileList.add(fileName);

        // Créer un fichier temporaire pour stocker le fichier reçu
        File tempFile = new File("temp_" + fileName);
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            byte[] buffer = new byte[1024 * 1024]; // Buffer de 1 Mo
            int bytesRead;
            while (fileSize > 0 && (bytesRead = dis.read(buffer, 0, (int) Math.min(buffer.length, fileSize))) != -1) {
                fos.write(buffer, 0, bytesRead);
                fileSize -= bytesRead;
            }
        }
        System.out.println("Fichier reçu et stocké temporairement : " + tempFile.getName());

        // Diviser le fichier en 3 parties et les envoyer aux sous-serveurs
        splitAndSendToSubServers(tempFile, fileName);

        // Supprimer le fichier temporaire
        tempFile.delete();
        System.out.println("Fichier temporaire supprimé.");
    }

    private static void handleReceiveCommand(Socket clientSocket, DataInputStream dis, DataOutputStream dos) throws IOException {
        String fileName = dis.readUTF();

        if (!fileList.contains(fileName)) {
            dos.writeBoolean(false); // Fichier non trouvé
            System.out.println("Fichier non trouvé : " + fileName);
            return;
        }

        dos.writeBoolean(true); // Fichier trouvé

        // Récupérer les parties du fichier des sous-serveurs
        File assembledFile = assembleFileFromSubServers(fileName);

        // Envoyer le fichier réassemblé au client
        sendFileToClient(clientSocket, assembledFile);

        // Supprimer le fichier assemblé après l'envoi
        assembledFile.delete();
        System.out.println("Fichier assemblé supprimé.");

        // Retirer le fichier de la liste des fichiers disponibles
        fileList.remove(fileName);
        System.out.println("Fichier " + fileName + " retiré de la liste.");

        // Supprimer les parties du fichier des sous-serveurs
        for (int i = 0; i < 3; i++) {
            String partFileName = fileName + "_part" + (i + 1);
            deleteFileFromSubServer(partFileName, SUB_SERVERS[i]);
        }
    }

    private static void handleDeleteFile(DataInputStream dis, DataOutputStream dos) throws IOException {
        String fileName = dis.readUTF();
        boolean allDeleted = true;
    
        for (int i = 0; i < 3; i++) {
            String partFileName = STORAGE_DIR + "/" + fileName + "_part" + (i + 1);
            File partFile = new File(partFileName);
    
            if (partFile.exists()) {
                if (!partFile.delete()) {
                    allDeleted = false;
                    System.out.println("Erreur lors de la suppression de " + partFileName);
                }
            } else {
                System.out.println("Fichier non trouvé : " + partFileName);
            }
        }
    
        if (allDeleted) {
            dos.writeUTF("Fichiers supprimés avec succès.");
        } else {
            dos.writeUTF("Erreur lors de la suppression des fichiers.");
        }
    }

    private static void deleteFileFromSubServer(String fileName, String subServerAddress) throws IOException {
        String[] subServerInfo = subServerAddress.split(":");
        String host = subServerInfo[0];
        int port = Integer.parseInt(subServerInfo[1]);

        try (Socket socket = new Socket(host, port);
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {

            // Envoyer la commande DELETE
            dos.writeUTF("DELETE");
            dos.writeUTF(fileName);

            System.out.println("Demande de suppression de " + fileName + " sur " + subServerAddress);
        } catch (IOException e) {
            System.out.println("Erreur lors de la suppression de " + fileName + " : " + e.getMessage());
        }
    }

    

   
    
    
    private static void sendFileToClient(Socket clientSocket, File file) throws IOException {
        System.out.println("Envoi du fichier " + file.getName() + " au client...");
    
        try (DataOutputStream dos = new DataOutputStream(clientSocket.getOutputStream());
             FileInputStream fis = new FileInputStream(file)) {
    
            dos.writeUTF(file.getName());
    
            
            long fileSize = file.length();
            dos.writeLong(fileSize);
            System.out.println("Taille du fichier envoyé : " + fileSize + " octets");
    
        
            byte[] buffer = new byte[1024 * 1024]; 
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                dos.write(buffer, 0, bytesRead);
            }
        }
        System.out.println("Fichier envoyé avec succès.");
    }
  
    private static void splitAndSendToSubServers(File file, String fileName) {
        long fileSize = file.length();
        long partSize = fileSize / 3;
        long remainder = fileSize % 3;
    
        try (FileInputStream fis = new FileInputStream(file)) {
            for (int i = 0; i < 3; i++) {
                long start = i * partSize;
                long end = (i == 2) ? fileSize : start + partSize;
    
                if (i == 2) {
                    end += remainder;
                }
    
                String partFileName = STORAGE_DIR + "/" + fileName + "_part" + (i + 1);
                File partFile = new File(partFileName);
    
                try (FileOutputStream fos = new FileOutputStream(partFile)) {
                    fis.getChannel().position(start); 
                    byte[] buffer = new byte[1024 * 1024]; 
                    int bytesRead;
                    long remaining = end - start;
    
                    while (remaining > 0 && (bytesRead = fis.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
                        fos.write(buffer, 0, bytesRead);
                        remaining -= bytesRead;
                    }
                }
    
                System.out.println("Partie " + (i + 1) + " créée : " + partFileName);
            }
        } catch (IOException e) {
            System.out.println("Erreur lors de la division du fichier : " + e.getMessage());
        }
    }
    
    private static File assembleFileFromSubServers(String fileName) throws IOException {
        File assembledFile = new File("assembled_" + fileName);
        System.out.println("Assemblage du fichier " + assembledFile.getName());
    
        try (FileOutputStream fos = new FileOutputStream(assembledFile)) {
            long totalSize = 0; 
    
            for (int i = 0; i < 3; i++) {
                String partFileName = fileName + "_part" + (i + 1);
                System.out.println("Récupération de la partie : " + partFileName);
    
                File partFile = retrieveFileFromSubServer(partFileName, SUB_SERVERS[i]);
                if (partFile != null) {
                    try (FileInputStream fis = new FileInputStream(partFile)) {
                        byte[] buffer = new byte[1024 * 1024]; // Buffer de 1 Mo
                        int bytesRead;
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            fos.write(buffer, 0, bytesRead);
                            totalSize += bytesRead;
                        }
                    }
                    System.out.println("Partie " + (i + 1) + " ajoutée au fichier assemblé.");
                    partFile.delete(); // Supprimer la partie après l'avoir ajoutée
                } else {
                    System.out.println("Partie " + (i + 1) + " non trouvée.");
                    throw new IOException("Partie manquante : " + partFileName);
                }
            }
    
            System.out.println("Fichier assemblé créé : " + assembledFile.getName() + " (" + totalSize + " octets)");
        } catch (IOException e) {
            System.out.println("Erreur lors de l'assemblage du fichier : " + e.getMessage());
            throw e;
        }
    
        return assembledFile;
    }

    
    private static File retrieveFileFromSubServer(String fileName, String subServerAddress) throws IOException {
        String[] subServerInfo = subServerAddress.split(":");
        String host = subServerInfo[0];
        int port = Integer.parseInt(subServerInfo[1]);
    
        System.out.println("Tentative de récupération de " + fileName + " depuis " + subServerAddress);
    
        try (Socket socket = new Socket(host, port);
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             DataInputStream dis = new DataInputStream(socket.getInputStream())) {
    
            // Demander la partie du fichier
            dos.writeUTF("RETRIEVE");
            dos.writeUTF(fileName);
    
            // Recevoir la taille de la partie
            long fileSize = dis.readLong();
            System.out.println("Taille de la partie " + fileName + " : " + fileSize + " octets");
    
            // Recevoir la partie du fichier
            File partFile = new File(fileName);
            try (FileOutputStream fos = new FileOutputStream(partFile)) {
                byte[] buffer = new byte[1024 * 1024]; // Buffer de 1 Mo
                int bytesRead;
                long totalBytesRead = 0;
                while (totalBytesRead < fileSize && (bytesRead = dis.read(buffer, 0, (int) Math.min(buffer.length, fileSize - totalBytesRead))) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;
                }
    
                if (totalBytesRead == fileSize) {
                    System.out.println("Partie " + fileName + " récupérée avec succès.");
                    return partFile;
                } else {
                    System.out.println("Erreur : la partie " + fileName + " est incomplète.");
                    return null;
                }
            }
        } catch (IOException e) {
            System.out.println("Erreur lors de la récupération de la partie " + fileName + " : " + e.getMessage());
            return null;
        }
    }
    
}