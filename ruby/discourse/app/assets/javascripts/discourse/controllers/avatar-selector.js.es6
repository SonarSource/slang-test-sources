import computed from "ember-addons/ember-computed-decorators";
import ModalFunctionality from "discourse/mixins/modal-functionality";
import { ajax } from "discourse/lib/ajax";
import { allowsImages } from "discourse/lib/utilities";
import { popupAjaxError } from "discourse/lib/ajax-error";

export default Ember.Controller.extend(ModalFunctionality, {
  @computed(
    "selected",
    "user.system_avatar_upload_id",
    "user.gravatar_avatar_upload_id",
    "user.custom_avatar_upload_id"
  )
  selectedUploadId(selected, system, gravatar, custom) {
    switch (selected) {
      case "system":
        return system;
      case "gravatar":
        return gravatar;
      default:
        return custom;
    }
  },

  @computed(
    "selected",
    "user.system_avatar_template",
    "user.gravatar_avatar_template",
    "user.custom_avatar_template"
  )
  selectedAvatarTemplate(selected, system, gravatar, custom) {
    switch (selected) {
      case "system":
        return system;
      case "gravatar":
        return gravatar;
      default:
        return custom;
    }
  },

  @computed()
  allowAvatarUpload() {
    return this.siteSettings.allow_uploaded_avatars && allowsImages();
  },

  actions: {
    uploadComplete() {
      this.set("selected", "uploaded");
    },

    refreshGravatar() {
      this.set("gravatarRefreshDisabled", true);

      return ajax(
        `/user_avatar/${this.get("user.username")}/refresh_gravatar.json`,
        { method: "POST" }
      )
        .then(result => {
          if (!result.gravatar_upload_id) {
            this.set("gravatarFailed", true);
          } else {
            this.set("gravatarFailed", false);
            this.get("user").setProperties(result);
          }
        })
        .finally(() => this.set("gravatarRefreshDisabled", false));
    },

    selectAvatar(url) {
      this.get("user")
        .selectAvatar(url)
        .then(() => window.location.reload())
        .catch(popupAjaxError);
    },

    saveAvatarSelection() {
      const selectedUploadId = this.get("selectedUploadId");
      const type = this.get("selected");

      this.get("user")
        .pickAvatar(selectedUploadId, type)
        .then(() => window.location.reload())
        .catch(popupAjaxError);
    }
  }
});
