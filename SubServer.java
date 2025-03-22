import java.io.*;
import java.net.*;

public class SubServer {
    private static int port; // Port sur lequel le sous-serveur écoute
    private static String STORAGE_DIR; // Répertoire de stockage des fichiers décomposés

    public SubServer(int port) {
        this.port = port;
    }

    public void start() {
        // Lire le fichier de configuration
        loadConfig();
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Sous-serveur en attente de connexions sur le port " + port);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Connexion reçue : " + clientSocket.getInetAddress());

                // Lancer un thread pour gérer la connexion
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
                if (line.startsWith("SUB_SERVER_PORT")) {
                    port = Integer.parseInt(line.split("=")[1].trim());
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

    private static void retrieveFilePart(Socket clientSocket) throws IOException {
        DataInputStream dis = new DataInputStream(clientSocket.getInputStream());
        String fileName = dis.readUTF();

        // Vérifier si le fichier existe dans le répertoire de stockage
        File file = new File(STORAGE_DIR + "/" + fileName);
        if (file.exists()) {
            try (FileInputStream fis = new FileInputStream(file);
                 DataOutputStream dos = new DataOutputStream(clientSocket.getOutputStream())) {
                dos.writeLong(file.length());
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    dos.write(buffer, 0, bytesRead);
                }
                System.out.println("Partie de fichier envoyée : " + file.getAbsolutePath());
            }
        } else {
            System.out.println("Partie de fichier non trouvée : " + file.getAbsolutePath());
        }
    }

    private static void handleDeleteCommand(Socket clientSocket, DataInputStream dis) throws IOException {
        String fileName = dis.readUTF();

        // Vérifier si le fichier existe dans le répertoire de stockage
        File file = new File(STORAGE_DIR + "/" + fileName);
        if (file.exists()) {
            if (file.delete()) {
                System.out.println("Fichier supprimé : " + file.getAbsolutePath());
            } else {
                System.out.println("Erreur lors de la suppression du fichier : " + file.getAbsolutePath());
            }
        } else {
            System.out.println("Le fichier " + file.getAbsolutePath() + " n'existe pas.");
        }
    }

    private void handleDeleteFile(DataInputStream dataInputStream, DataOutputStream dataOutputStream) throws IOException {
        String baseName = dataInputStream.readUTF(); // Nom de base du fichier (ex. "test.txt")
        boolean allDeleted = true; // Indique si toutes les suppressions ont réussi
        boolean atLeastOneFound = false; // Indique si au moins un fichier a été trouvé

        // Lister les fichiers dans le répertoire de stockage
        File storageDir = new File(STORAGE_DIR);
        File[] files = storageDir.listFiles();
        if (files != null) {
            for (File file : files) {
                // Vérifie si le fichier correspond au nom de base
                if (file.getName().startsWith(baseName)) {
                    atLeastOneFound = true; // Au moins un fichier trouvé
                    System.out.println("Suppression du fichier : " + file.getAbsolutePath());
                    if (!file.delete()) {
                        System.out.println("Erreur lors de la suppression du fichier : " + file.getAbsolutePath());
                        allDeleted = false; // Échec si un fichier ne peut pas être supprimé
                    }
                }
            }
        }

        // Envoyer la réponse au serveur principal
        if (atLeastOneFound) {
            if (allDeleted) {
                System.out.println("Tous les fichiers liés à " + baseName + " ont été supprimés avec succès.");
                dataOutputStream.writeUTF("Fichiers supprimés avec succès.");
            } else {
                System.out.println("Certaines parties de " + baseName + " n'ont pas pu être supprimées.");
                dataOutputStream.writeUTF("Échec de la suppression de certains fichiers.");
            }
        } else {
            System.out.println("Aucun fichier trouvé correspondant au nom de base : " + baseName);
            dataOutputStream.writeUTF("Aucun fichier trouvé.");
        }
    }

    private static void handleClient(Socket clientSocket) {
        try (DataInputStream dis = new DataInputStream(clientSocket.getInputStream())) {
            String command = dis.readUTF();

            switch (command) {
                case "STORE":
                    storeFilePart(clientSocket);
                    break;
                case "RETRIEVE":
                    retrieveFilePart(clientSocket);
                    break;
                case "DELETE":
                    handleDeleteCommand(clientSocket, dis);
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

    private static void storeFilePart(Socket clientSocket) throws IOException {
        DataInputStream dis = new DataInputStream(clientSocket.getInputStream());
        String fileName = dis.readUTF();
        long fileSize = dis.readLong();

        // Créer le fichier dans le répertoire de stockage
        File file = new File(STORAGE_DIR + "/" + fileName);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            byte[] buffer = new byte[1024 * 1024]; // Buffer de 1 Mo
            int bytesRead;
            while (fileSize > 0 && (bytesRead = dis.read(buffer, 0, (int) Math.min(buffer.length, fileSize))) != -1) {
                fos.write(buffer, 0, bytesRead);
                fileSize -= bytesRead;
            }
        }
        System.out.println("Partie de fichier reçue et stockée : " + file.getAbsolutePath());
    }

    // Méthode principale pour démarrer le sous-serveur
    public static void main(String[] args) {
        // Charger la configuration
        loadConfig();

        // Démarrer le sous-serveur
        SubServer subServer = new SubServer(port);
        subServer.start();
    }
}