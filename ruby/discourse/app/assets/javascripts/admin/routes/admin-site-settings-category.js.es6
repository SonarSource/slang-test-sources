export default Discourse.Route.extend({
  model(params) {
    // The model depends on user input, so let the controller do the work:
    this.controllerFor("adminSiteSettingsCategory").set(
      "categoryNameKey",
      params.category_id
    );
    return Ember.Object.create({
      nameKey: params.category_id,
      name: I18n.t("admin.site_settings.categories." + params.category_id),
      siteSettings: this.controllerFor("adminSiteSettingsCategory").get(
        "filteredContent"
      )
    });
  }
});
