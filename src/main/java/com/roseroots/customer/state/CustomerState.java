package com.roseroots.customer.state;

import com.roseroots.customer.models.ServiceMode;
import com.roseroots.customer.models.MenuItem;
import com.roseroots.customer.models.TerminalSession;
import java.util.Locale;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import com.roseroots.customer.models.AllergyDeclaration;
import com.roseroots.customer.models.ConfiguredDish;
import java.util.Objects;

public final class CustomerState {
    private Locale locale = Locale.ENGLISH;
    private ServiceMode serviceMode;
    private TerminalSession terminalSession;
    private final CartDraft cart = new CartDraft();
    private final AssistanceState assistance;

    public CustomerState() {
        this(new AssistanceState(com.roseroots.customer.services.AssistanceService.unavailable(),
                java.time.Clock.systemUTC(), Runnable::run));
    }

    public CustomerState(AssistanceState assistance) {
        this.assistance = Objects.requireNonNull(assistance);
    }

    public AssistanceState assistance() { return assistance; }
    private final Map<String, AllergyDeclaration> declarations = new HashMap<>();
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
        return cart.count() > 0 && (!next.allowsCart() || terminalSession == null
                || next.mode() != terminalSession.mode()
                || !Objects.equals(next.cartContextId(), terminalSession.cartContextId()));
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
        if (terminalSession == null || !terminalSession.allowsCart()
                || item.availability() != MenuItem.Availability.AVAILABLE) {
            return false;
        }
        return saveDish(null, new ConfiguredDish(item, 1, ConfiguredDish.defaults(item), "", sessionAllergy()));
    }

    public AllergyDeclaration sessionAllergy() {
        return terminalSession == null ? AllergyDeclaration.unreviewed()
                : declarations.getOrDefault(terminalSession.cartContextId(), AllergyDeclaration.unreviewed());
    }

    public void rememberAllergy(AllergyDeclaration declaration) {
        if (terminalSession != null && terminalSession.allowsCart()) {
            declarations.put(terminalSession.cartContextId(), declaration);
        }
    }

    public boolean saveDish(UUID line, ConfiguredDish dish) {
        if (terminalSession == null || !terminalSession.allowsCart()
                || dish.item().availability() != MenuItem.Availability.AVAILABLE
                || (line != null && cart.line(line) == null)) {
            return false;
        }
        cart.save(line, dish);
        return true;
    }

    public void removeDish(UUID line) { cart.remove(line); }

    public boolean clearDraft(boolean confirmed) {
        if (!confirmed) {
            return false;
        }
        cart.clear();
        return true;
    }

    public boolean changeQuantity(UUID id, int quantity) {
        var line = cart.line(id);
        if (line == null || quantity < 1 || quantity > ConfiguredDish.MAX_QUANTITY) {
            return false;
        }
        if (quantity < line.quantity()) {
            cart.save(id, line.dish().withQuantity(quantity));
            return true;
        }
        return saveDish(id, line.dish().withQuantity(quantity));
    }

    public MenuBrowseState menu() { return menu; }
}
