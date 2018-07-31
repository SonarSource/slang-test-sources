import ModalFunctionality from "discourse/mixins/modal-functionality";

const _buttons = [];

const alwaysTrue = () => true;

function identity() {}

function addBulkButton(action, key, opts) {
  opts = opts || {};

  const btn = {
    action,
    label: `topics.bulk.${key}`,
    icon: opts.icon,
    buttonVisible: opts.buttonVisible || alwaysTrue,
    class: opts.class
  };

  _buttons.push(btn);
}

// Default buttons
addBulkButton("showChangeCategory", "change_category", { icon: "pencil" });
addBulkButton("closeTopics", "close_topics", { icon: "lock" });
addBulkButton("archiveTopics", "archive_topics", { icon: "folder" });
addBulkButton("showNotificationLevel", "notification_level", {
  icon: "d-regular"
});
addBulkButton("resetRead", "reset_read", { icon: "backward" });
addBulkButton("unlistTopics", "unlist_topics", {
  icon: "eye-slash",
  buttonVisible: topics => topics.some(t => t.visible)
});
addBulkButton("relistTopics", "relist_topics", {
  icon: "eye",
  buttonVisible: topics => topics.some(t => !t.visible)
});
if (Discourse.SiteSettings.tagging_enabled) {
  addBulkButton("showTagTopics", "change_tags", { icon: "tag" });
  addBulkButton("showAppendTagTopics", "append_tags", { icon: "tag" });
}
addBulkButton("deleteTopics", "delete", { icon: "trash", class: "btn-danger" });

// Modal for performing bulk actions on topics
export default Ember.Controller.extend(ModalFunctionality, {
  tags: null,

  emptyTags: Ember.computed.empty("tags"),
  categoryId: Ember.computed.alias("model.category.id"),

  onShow() {
    const topics = this.get("model.topics");
    // const relistButtonIndex = _buttons.findIndex(b => b.action === 'relistTopics');

    this.set("buttons", _buttons.filter(b => b.buttonVisible(topics)));
    this.set("modal.modalClass", "topic-bulk-actions-modal small");
    this.send("changeBulkTemplate", "modal/bulk-actions-buttons");
  },

  perform(operation) {
    this.set("loading", true);

    const topics = this.get("model.topics");
    return Discourse.Topic.bulkOperation(topics, operation)
      .then(result => {
        this.set("loading", false);
        if (result && result.topic_ids) {
          return result.topic_ids.map(t => topics.findBy("id", t));
        }
        return result;
      })
      .catch(() => {
        bootbox.alert(I18n.t("generic_error"));
        this.set("loading", false);
      });
  },

  forEachPerformed(operation, cb) {
    this.perform(operation).then(topics => {
      if (topics) {
        topics.forEach(cb);
        (this.get("refreshClosure") || identity)();
        this.send("closeModal");
      }
    });
  },

  performAndRefresh(operation) {
    return this.perform(operation).then(() => {
      (this.get("refreshClosure") || identity)();
      this.send("closeModal");
    });
  },

  actions: {
    showTagTopics() {
      this.set("tags", "");
      this.set("action", "changeTags");
      this.set("label", "change_tags");
      this.set("title", "choose_new_tags");
      this.send("changeBulkTemplate", "bulk-tag");
    },

    changeTags() {
      this.performAndRefresh({ type: "change_tags", tags: this.get("tags") });
    },

    showAppendTagTopics() {
      this.set("tags", "");
      this.set("action", "appendTags");
      this.set("label", "append_tags");
      this.set("title", "choose_append_tags");
      this.send("changeBulkTemplate", "bulk-tag");
    },

    appendTags() {
      this.performAndRefresh({ type: "append_tags", tags: this.get("tags") });
    },

    showChangeCategory() {
      this.send("changeBulkTemplate", "modal/bulk-change-category");
    },

    showNotificationLevel() {
      this.send("changeBulkTemplate", "modal/bulk-notification-level");
    },

    deleteTopics() {
      this.performAndRefresh({ type: "delete" });
    },

    closeTopics() {
      this.forEachPerformed({ type: "close" }, t => t.set("closed", true));
    },

    archiveTopics() {
      this.forEachPerformed({ type: "archive" }, t => t.set("archived", true));
    },

    unlistTopics() {
      this.forEachPerformed({ type: "unlist" }, t => t.set("visible", false));
    },

    relistTopics() {
      this.forEachPerformed({ type: "relist" }, t => t.set("visible", true));
    },

    changeCategory() {
      const categoryId = parseInt(this.get("newCategoryId"), 10) || 0;
      const category = Discourse.Category.findById(categoryId);

      this.perform({ type: "change_category", category_id: categoryId }).then(
        topics => {
          topics.forEach(t => t.set("category", category));
          (this.get("refreshClosure") || identity)();
          this.send("closeModal");
        }
      );
    },

    resetRead() {
      this.performAndRefresh({ type: "reset_read" });
    }
  }
});

export { addBulkButton };
