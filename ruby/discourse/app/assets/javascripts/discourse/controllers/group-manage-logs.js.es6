import {
  default as computed,
  observes
} from "ember-addons/ember-computed-decorators";

export default Ember.Controller.extend({
  group: Ember.inject.controller(),
  loading: false,
  offset: 0,
  application: Ember.inject.controller(),

  init() {
    this._super();
    this.set("filters", Ember.Object.create());
  },

  @computed(
    "filters.action",
    "filters.acting_user",
    "filters.target_user",
    "filters.subject"
  )
  filterParams(action, acting_user, target_user, subject) {
    return { action, acting_user, target_user, subject };
  },

  @observes(
    "filters.action",
    "filters.acting_user",
    "filters.target_user",
    "filters.subject"
  )
  _refreshModel() {
    this.get("group.model")
      .findLogs(0, this.get("filterParams"))
      .then(results => {
        this.set("offset", 0);

        this.get("model").setProperties({
          logs: results.logs,
          all_loaded: results.all_loaded
        });
      });
  },

  @observes("model.all_loaded")
  _showFooter() {
    this.set("application.showFooter", this.get("model.all_loaded"));
  },

  reset() {
    this.setProperties({
      offset: 0,
      filters: Ember.Object.create()
    });
  },

  actions: {
    loadMore() {
      if (this.get("model.all_loaded")) return;

      this.set("loading", true);

      this.get("group.model")
        .findLogs(this.get("offset") + 1, this.get("filterParams"))
        .then(results => {
          results.logs.forEach(result =>
            this.get("model.logs").addObject(result)
          );
          this.incrementProperty("offset");
          this.set("model.all_loaded", results.all_loaded);
        })
        .finally(() => {
          this.set("loading", false);
        });
    },

    clearFilter(key) {
      this.set(`filters.${key}`, "");
    }
  }
});
