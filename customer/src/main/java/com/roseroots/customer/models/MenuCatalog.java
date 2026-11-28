package com.roseroots.customer.models;

import java.util.List;

public record MenuCatalog(List<MenuCategory> categories, List<MenuItem> items, boolean developmentFixtures) {
    public MenuCatalog {
        categories = List.copyOf(categories);
        items = List.copyOf(items);
    }

    public List<MenuCategory> childrenOf(String parentId) {
        return categories.stream()
                .filter(category -> java.util.Objects.equals(category.parentId(), parentId))
                .toList();
    }

    public boolean belongsTo(MenuItem item, String categoryId) {
        String current = item.categoryId();
        var visited = new java.util.HashSet<String>();
        while (current != null && visited.add(current)) {
            if (current.equals(categoryId)) {
                return true;
            }
            var category = category(current);
            current = category == null ? null : category.parentId();
        }
        return false;
    }

    public MenuCategory category(String id) {
        return categories.stream().filter(category -> category.id().equals(id)).findFirst().orElse(null);
    }

    public MenuItem item(String id) {
        return items.stream().filter(item -> item.id().equals(id)).findFirst().orElse(null);
    }
}
