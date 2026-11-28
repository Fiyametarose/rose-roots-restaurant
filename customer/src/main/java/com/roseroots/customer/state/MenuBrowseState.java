package com.roseroots.customer.state;

import com.roseroots.customer.models.MenuCatalog;
import com.roseroots.customer.services.MenuSearch.Filters;

public final class MenuBrowseState {
    private String categoryId = "main-courses";
    private String subcategoryId;
    private String itemId;
    private Filters filters = Filters.defaults();
    private MenuCatalog catalog;
    private double scrollPosition;

    public String categoryId() { return categoryId; }

    public String subcategoryId() { return subcategoryId; }

    public void selectCategory(String categoryId) {
        if (!java.util.Objects.equals(this.categoryId, categoryId)) {
            this.categoryId = categoryId;
            subcategoryId = null;
        }
    }

    public void selectSubcategory(String subcategoryId) { this.subcategoryId = subcategoryId; }

    public String itemId() { return itemId; }

    public void selectItem(String itemId) { this.itemId = itemId; }

    public Filters filters() { return filters; }

    public void setFilters(Filters filters) { this.filters = filters; }

    public void resetFilters() {
        filters = Filters.defaults();
        subcategoryId = null;
    }

    public double scrollPosition() { return scrollPosition; }

    public void rememberScroll(double position) { scrollPosition = position; }

    public MenuCatalog catalog() { return catalog; }

    public void setCatalog(MenuCatalog catalog) {
        this.catalog = catalog;
        var roots = catalog.childrenOf(null);
        if (roots.stream().noneMatch(category -> category.id().equals(categoryId))) {
            selectCategory(roots.isEmpty() ? null : roots.getFirst().id());
        }
        if (subcategoryId != null && catalog.childrenOf(categoryId).stream()
                .noneMatch(category -> category.id().equals(subcategoryId))) {
            subcategoryId = null;
        }
    }
}
