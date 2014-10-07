package ru.telecortech.jphonelite;

/**
 * @author MBUILIN
 * @since 25.06.14
 */
public interface EventListener {

    public void registered();

    public void unregistered();

    public void incoming(String number, String record);

    public void accepted();

    public void rejected();

    public void transferred();

    public void newMessage(String message);

}
