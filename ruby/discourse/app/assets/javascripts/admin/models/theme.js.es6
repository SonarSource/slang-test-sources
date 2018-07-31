import RestModel from "discourse/models/rest";
import { default as computed } from "ember-addons/ember-computed-decorators";

const THEME_UPLOAD_VAR = 2;

const Theme = RestModel.extend({
  FIELDS_IDS: [0, 1],

  @computed("theme_fields")
  themeFields(fields) {
    if (!fields) {
      this.set("theme_fields", []);
      return {};
    }

    let hash = {};
    fields.forEach(field => {
      if (!field.type_id || this.get("FIELDS_IDS").includes(field.type_id)) {
        hash[this.getKey(field)] = field;
      }
    });
    return hash;
  },

  @computed("theme_fields", "theme_fields.@each")
  uploads(fields) {
    if (!fields) {
      return [];
    }
    return fields.filter(
      f => f.target === "common" && f.type_id === THEME_UPLOAD_VAR
    );
  },

  getKey(field) {
    return `${field.target} ${field.name}`;
  },

  hasEdited(target, name) {
    if (name) {
      return !Em.isEmpty(this.getField(target, name));
    } else {
      let fields = this.get("theme_fields") || [];
      return fields.any(
        field => field.target === target && !Em.isEmpty(field.value)
      );
    }
  },

  getError(target, name) {
    let themeFields = this.get("themeFields");
    let key = this.getKey({ target, name });
    let field = themeFields[key];
    return field ? field.error : "";
  },

  getField(target, name) {
    let themeFields = this.get("themeFields");
    let key = this.getKey({ target, name });
    let field = themeFields[key];
    return field ? field.value : "";
  },

  removeField(field) {
    this.set("changed", true);

    field.upload_id = null;
    field.value = null;

    return this.saveChanges("theme_fields");
  },

  setField(target, name, value, upload_id, type_id) {
    this.set("changed", true);
    let themeFields = this.get("themeFields");
    let field = { name, target, value, upload_id, type_id };

    // slow path for uploads and so on
    if (type_id && type_id > 1) {
      let fields = this.get("theme_fields");
      let existing = fields.find(
        f => f.target === target && f.name === name && f.type_id === type_id
      );
      if (existing) {
        existing.value = value;
        existing.upload_id = upload_id;
      } else {
        fields.push(field);
      }
      return;
    }

    // fast path
    let key = this.getKey({ target, name });
    let existingField = themeFields[key];
    if (!existingField) {
      this.theme_fields.push(field);
      themeFields[key] = field;
    } else {
      existingField.value = value;
    }
  },

  @computed("childThemes.@each")
  child_theme_ids(childThemes) {
    if (childThemes) {
      return childThemes.map(theme => Ember.get(theme, "id"));
    }
  },

  removeChildTheme(theme) {
    const childThemes = this.get("childThemes");
    childThemes.removeObject(theme);
    return this.saveChanges("child_theme_ids");
  },

  addChildTheme(theme) {
    let childThemes = this.get("childThemes");
    if (!childThemes) {
      childThemes = [];
      this.set("childThemes", childThemes);
    }
    childThemes.removeObject(theme);
    childThemes.pushObject(theme);
    return this.saveChanges("child_theme_ids");
  },

  @computed("name", "default")
  description: function(name, isDefault) {
    if (isDefault) {
      return I18n.t("admin.customize.theme.default_name", { name: name });
    } else {
      return name;
    }
  },

  checkForUpdates() {
    return this.save({ remote_check: true }).then(() =>
      this.set("changed", false)
    );
  },

  updateToLatest() {
    return this.save({ remote_update: true }).then(() =>
      this.set("changed", false)
    );
  },

  changed: false,

  saveChanges() {
    const hash = this.getProperties.apply(this, arguments);
    return this.save(hash).then(() => this.set("changed", false));
  },

  saveSettings(name, value) {
    const settings = {};
    settings[name] = value;
    return this.save({ settings });
  }
});

export default Theme;
