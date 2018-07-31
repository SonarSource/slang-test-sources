import computed from "ember-addons/ember-computed-decorators";

export default Ember.Component.extend({
  tagName: "",

  @computed("type")
  label(type) {
    return I18n.t(`groups.manage.logs.${type}`);
  },

  @computed("value", "type")
  filterText(value, type) {
    return type === "action"
      ? I18n.t(`group_histories.actions.${value}`)
      : value;
  },

  actions: {
    clearFilter(param) {
      this.sendAction("clearFilter", param);
    }
  }
});
