import {
  displayErrorForUpload,
  validateUploadedFiles
} from "discourse/lib/utilities";

export default Em.Mixin.create({
  uploading: false,
  uploadProgress: 0,

  uploadDone() {
    Em.warn("You should implement `uploadDone`");
  },

  validateUploadedFilesOptions() {
    return {};
  },

  _initialize: function() {
    const $upload = this.$(),
      csrf = Discourse.Session.currentProp("csrfToken"),
      uploadUrl = Discourse.getURL(
        this.getWithDefault("uploadUrl", "/uploads")
      ),
      reset = () => this.setProperties({ uploading: false, uploadProgress: 0 });

    $upload.on("fileuploaddone", (e, data) => {
      let upload = data.result;
      this.uploadDone(upload);
      reset();
    });

    $upload.fileupload({
      url:
        uploadUrl +
        ".json?client_id=" +
        this.messageBus.clientId +
        "&authenticity_token=" +
        encodeURIComponent(csrf),
      dataType: "json",
      dropZone: $upload,
      pasteZone: $upload
    });

    $upload.on("fileuploaddrop", (e, data) => {
      if (data.files.length > 10) {
        bootbox.alert(I18n.t("post.errors.too_many_dragged_and_dropped_files"));
        return false;
      } else {
        return true;
      }
    });

    $upload.on("fileuploadsubmit", (e, data) => {
      const opts = _.merge(
        { bypassNewUserRestriction: true },
        this.validateUploadedFilesOptions()
      );
      const isValid = validateUploadedFiles(data.files, opts);
      let form = { type: this.get("type") };
      if (this.get("data")) {
        form = $.extend(form, this.get("data"));
      }
      data.formData = form;
      this.setProperties({ uploadProgress: 0, uploading: isValid });
      return isValid;
    });

    $upload.on("fileuploadprogressall", (e, data) => {
      const progress = parseInt((data.loaded / data.total) * 100, 10);
      this.set("uploadProgress", progress);
    });

    $upload.on("fileuploadfail", (e, data) => {
      displayErrorForUpload(data);
      reset();
    });
  }.on("didInsertElement"),

  _destroy: function() {
    this.messageBus.unsubscribe("/uploads/" + this.get("type"));
    const $upload = this.$();
    try {
      $upload.fileupload("destroy");
    } catch (e) {
      /* wasn't initialized yet */
    }
    $upload.off();
  }.on("willDestroyElement")
});
