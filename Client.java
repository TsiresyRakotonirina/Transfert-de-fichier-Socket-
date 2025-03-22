import java.io.*;
import java.net.*;

public class Client {
    private static String SERVER_ADDRESS;
    private static int SERVER_PORT;
    private static String DOWNLOAD_DIR;

    public static void main(String[] args) {
        // Lire le fichier de configuration
        loadConfig();

        // Vérifier les arguments de la ligne de commande
        if (args.length < 1) {
            System.out.println("Usage: java Client <command> [options]");
            System.out.println("Commands:");
            System.out.println("  LIST                        Lister les fichiers disponibles");
            System.out.println("  SEND <file>                 Envoyer un fichier au serveur");
            System.out.println("  RECEIVE <file>              Récupérer un fichier du serveur");
            System.out.println("  DELETE <file>               Supprimer un fichier");
            return;
        }

        String command = args[0].toUpperCase();

        try (Socket socket = new Socket(SERVER_ADDRESS, SERVER_PORT);
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             DataInputStream dis = new DataInputStream(socket.getInputStream())) {

            switch (command) {
                case "LIST":
                    handleListCommand(dos, dis);
                    break;
                case "SEND":
                    if (args.length < 2) {
                        System.out.println("Usage: java Client SEND <file>");
                        return;
                    }
                    handleSendCommand(dos, args[1]);
                    break;
                case "RECEIVE":
                    if (args.length < 2) {
                        System.out.println("Usage: java Client RECEIVE <file>");
                        return;
                    }
                    handleReceiveCommand(dos, dis, args[1]);
                    break;
                case "DELETE":
                    if (args.length < 2) {
                        System.out.println("Usage: java Client DELETE <file>");
                        return;
                    }
                    deleteFile(dos, dis, args[1]);
                    break;
                default:
                    System.out.println("Commande non reconnue : " + command);
                    break;
            }
        } catch (IOException e) {
            System.err.println("Erreur de connexion au serveur : " + e.getMessage());
        }
    }

    private static void loadConfig() {
        try (BufferedReader reader = new BufferedReader(new FileReader("config.txt"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("PORT")) {
                    SERVER_PORT = Integer.parseInt(line.split("=")[1].trim());
                } else if (line.startsWith("SERVER_ADDRESS")) {
                    SERVER_ADDRESS = line.split("=")[1].trim();
                } else if (line.startsWith("DOWNLOAD_DIR")) {
                    DOWNLOAD_DIR = line.split("=")[1].trim();
                    // Créer le répertoire s'il n'existe pas
                    File downloadDir = new File(DOWNLOAD_DIR);
                    if (!downloadDir.exists()) {
                        if (downloadDir.mkdirs()) {
                            System.out.println("Répertoire de téléchargement créé : " + DOWNLOAD_DIR);
                        } else {
                            System.err.println("Erreur lors de la création du répertoire de téléchargement : " + DOWNLOAD_DIR);
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Erreur lors du chargement du fichier de configuration : " + e.getMessage());
        }
    }

    private static void handleListCommand(DataOutputStream dos, DataInputStream dis) throws IOException {
        // Envoyer la commande LIST
        dos.writeUTF("LIST");

        // Recevoir la liste des fichiers
        int fileCount = dis.readInt();
        System.out.println("Fichiers disponibles sur le serveur :");
        for (int i = 0; i < fileCount; i++) {
            String fileName = dis.readUTF();
            System.out.println("- " + fileName);
        }
    }

    private static void handleSendCommand(DataOutputStream dos, String filePath) throws IOException {
        File fileToSend = new File(filePath);
        if (!fileToSend.exists() || !fileToSend.isFile()) {
            System.out.println("Le fichier " + filePath + " n'existe pas ou n'est pas un fichier valide.");
            return;
        }

        // Envoyer la commande SEND
        dos.writeUTF("SEND");

        // Envoyer le nom du fichier
        dos.writeUTF(fileToSend.getName());

        // Envoyer la taille du fichier
        dos.writeLong(fileToSend.length());

        // Envoyer le contenu du fichier
        try (FileInputStream fis = new FileInputStream(fileToSend)) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                dos.write(buffer, 0, bytesRead);
            }
        }

        System.out.println("Fichier envoyé au serveur : " + fileToSend.getName());
    }

    private static void handleReceiveCommand(DataOutputStream dos, DataInputStream dis, String fileName) throws IOException {
        // Envoyer la commande RECEIVE
        dos.writeUTF("RECEIVE");

        // Envoyer le nom du fichier à récupérer
        dos.writeUTF(fileName);

        // Vérifier si le fichier existe sur le serveur
        boolean fileExists = dis.readBoolean();
        if (!fileExists) {
            System.out.println("Le fichier " + fileName + " n'existe pas sur le serveur.");
            return;
        }

        // Recevoir le nom du fichier
        String receivedFileName = dis.readUTF();
        System.out.println("Réception du fichier : " + receivedFileName);

        // Recevoir la taille du fichier
        long fileSize = dis.readLong();
        System.out.println("Taille du fichier : " + fileSize + " octets");

        // Recevoir le contenu du fichier
        File outputFile = new File(DOWNLOAD_DIR + "/" + receivedFileName);
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while (fileSize > 0 && (bytesRead = dis.read(buffer, 0, (int) Math.min(buffer.length, fileSize))) != -1) {
                fos.write(buffer, 0, bytesRead);
                fileSize -= bytesRead;
            }
        }

        System.out.println("Fichier reçu : " + outputFile.getAbsolutePath());
    }

    private static void deleteFile(DataOutputStream dos, DataInputStream dis, String fileToDelete) {
        try {
            // Envoyer la commande DELETE
            dos.writeUTF("DELETE");

            // Envoyer le nom du fichier à supprimer
            dos.writeUTF(fileToDelete);

            // Recevoir la réponse du serveur
            String response = dis.readUTF();
            System.out.println(response);
        } catch (IOException e) {
            System.err.println("Erreur lors de la suppression du fichier : " + e.getMessage());
        }
    }
}