package com.roseroots.customer.state;

import com.roseroots.customer.models.ServiceMode;
import com.roseroots.customer.models.MenuItem;
import com.roseroots.customer.models.TerminalSession;
import java.util.Locale;
import java.util.Objects;

public final class CustomerState {
    private Locale locale = Locale.ENGLISH;
    private ServiceMode serviceMode;
    private TerminalSession terminalSession;
    private final CartDraft cart = new CartDraft();
    private final MenuBrowseState menu = new MenuBrowseState();

    public Locale locale() {
        return locale;
    }

    public void setLocale(Locale locale) {
        this.locale = Objects.requireNonNull(locale);
    }

    public ServiceMode serviceMode() {
        return serviceMode;
    }

    public void selectServiceMode(ServiceMode serviceMode) {
        Objects.requireNonNull(serviceMode);
        if (this.serviceMode != serviceMode) {
            if (cart.count() > 0) {
                throw new IllegalStateException("Cart belongs to the active session");
            }
            terminalSession = null;
        }
        this.serviceMode = serviceMode;
    }

    public TerminalSession terminalSession() { return terminalSession; }

    public boolean wouldDiscardCart(TerminalSession next) {
        return cart.count() > 0 && (!next.isOpen() || terminalSession == null
                || next.mode() != terminalSession.mode()
                || !Objects.equals(next.sessionId(), terminalSession.sessionId()));
    }

    public boolean activateSession(TerminalSession next, boolean discardConfirmed) {
        if (wouldDiscardCart(next)) {
            if (!discardConfirmed) {
                return false;
            }
            cart.clear();
        }
        terminalSession = Objects.requireNonNull(next);
        serviceMode = next.mode();
        return true;
    }

    public CartDraft cart() { return cart; }

    public boolean addToDraft(MenuItem item) {
        if (terminalSession == null || !terminalSession.isOpen()
                || item.availability() != MenuItem.Availability.AVAILABLE) {
            return false;
        }
        cart.add(item);
        return true;
    }

    public MenuBrowseState menu() { return menu; }
}
