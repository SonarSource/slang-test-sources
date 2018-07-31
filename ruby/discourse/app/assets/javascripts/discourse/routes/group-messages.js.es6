export default Discourse.Route.extend({
  titleToken() {
    return I18n.t("groups.messages");
  },

  model() {
    return this.modelFor("group");
  },

  afterModel(group) {
    if (
      !group.get("is_group_user") &&
      !(this.currentUser && this.currentUser.admin)
    ) {
      this.transitionTo("group.members", group);
    }
  }
});
