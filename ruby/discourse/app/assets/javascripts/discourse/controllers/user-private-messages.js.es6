import computed from "ember-addons/ember-computed-decorators";
import Topic from "discourse/models/topic";

export default Ember.Controller.extend({
  application: Ember.inject.controller(),
  userTopicsList: Ember.inject.controller("user-topics-list"),
  user: Ember.inject.controller(),

  pmView: false,
  viewingSelf: Em.computed.alias("user.viewingSelf"),
  isGroup: Em.computed.equal("pmView", "groups"),
  currentPath: Em.computed.alias("application.currentPath"),
  selected: Em.computed.alias("userTopicsList.selected"),
  bulkSelectEnabled: Em.computed.alias("userTopicsList.bulkSelectEnabled"),
  showToggleBulkSelect: true,
  pmTaggingEnabled: Ember.computed.alias("site.can_tag_pms"),
  tagId: null,

  showNewPM: function() {
    return (
      this.get("user.viewingSelf") &&
      Discourse.User.currentProp("can_send_private_messages")
    );
  }.property("user.viewingSelf"),

  @computed("selected.[]", "bulkSelectEnabled")
  hasSelection(selected, bulkSelectEnabled) {
    return bulkSelectEnabled && selected && selected.length > 0;
  },

  @computed("hasSelection", "pmView", "archive")
  canMoveToInbox(hasSelection, pmView, archive) {
    return hasSelection && (pmView === "archive" || archive);
  },

  @computed("hasSelection", "pmView", "archive")
  canArchive(hasSelection, pmView, archive) {
    return hasSelection && pmView !== "archive" && !archive;
  },

  bulkOperation(operation) {
    const selected = this.get("selected");
    var params = { type: operation };
    if (this.get("isGroup")) {
      params.group = this.get("groupFilter");
    }

    Topic.bulkOperation(selected, params).then(
      () => {
        const model = this.get("userTopicsList.model");
        const topics = model.get("topics");
        topics.removeObjects(selected);
        selected.clear();
        model.loadMore();
      },
      () => {
        bootbox.alert(I18n.t("user.messages.failed_to_move"));
      }
    );
  },

  actions: {
    archive() {
      this.bulkOperation("archive_messages");
    },
    toInbox() {
      this.bulkOperation("move_messages_to_inbox");
    },
    toggleBulkSelect() {
      this.toggleProperty("bulkSelectEnabled");
    },
    selectAll() {
      $("input.bulk-select:not(checked)").click();
    }
  }
});
