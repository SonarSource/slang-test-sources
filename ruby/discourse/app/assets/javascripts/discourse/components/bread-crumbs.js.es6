//  A breadcrumb including category drop downs
export default Ember.Component.extend({
  classNameBindings: ["hidden:hidden", ":category-breadcrumb"],
  tagName: "ol",
  parentCategory: Em.computed.alias("category.parentCategory"),

  parentCategories: Em.computed.filter("categories", function(c) {
    if (
      c.id === this.site.get("uncategorized_category_id") &&
      !this.siteSettings.allow_uncategorized_topics
    ) {
      // Don't show "uncategorized" if allow_uncategorized_topics setting is false.
      return false;
    }
    return !c.get("parentCategory");
  }),

  parentCategoriesSorted: function() {
    let cats = this.get("parentCategories");
    if (this.siteSettings.fixed_category_positions) {
      return cats;
    }

    return cats.sortBy("totalTopicCount").reverse();
  }.property("parentCategories"),

  hidden: function() {
    return this.site.mobileView && !this.get("category");
  }.property("category"),

  firstCategory: function() {
    return this.get("parentCategory") || this.get("category");
  }.property("parentCategory", "category"),

  secondCategory: function() {
    if (this.get("parentCategory")) return this.get("category");
    return null;
  }.property("category", "parentCategory"),

  childCategories: function() {
    if (this.get("hideSubcategories")) {
      return [];
    }
    var firstCategory = this.get("firstCategory");
    if (!firstCategory) {
      return [];
    }

    return this.get("categories").filter(function(c) {
      return c.get("parentCategory") === firstCategory;
    });
  }.property("firstCategory", "hideSubcategories")
});
