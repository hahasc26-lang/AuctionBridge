package net.minecraftforge.auctionbridge.database;

public class DatabaseQueryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DatabaseQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
