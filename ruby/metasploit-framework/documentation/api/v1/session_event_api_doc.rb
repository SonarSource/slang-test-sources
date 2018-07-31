require 'swagger/blocks'

module SessionEventApiDoc
  include Swagger::Blocks

  SESSION_ID_DESC = 'The ID of the session record that caused this event.'
  ETYPE_DESC = 'The type of session event that occurred.'
  ETYPE_ENUM = ['command', 'output', 'upload', 'download', 'filedelete']
  COMMAND_DESC = 'The command that was executed for this event.'
  OUTPUT_DESC = 'The resulting output of the executed command.'
  LOCAL_PATH_DESC = 'Path to the associated file for upload and download events.'
  LOCAL_PATH_EXAMPLE = '/path/to/file'
  REMOTE_PATH_DESC = 'Path to the associated file for upload, download, and filedelete events.'
  REMOTE_PATH_EXAMPLE = '/path/to/file'

# Swagger documentation for session events model
  swagger_schema :SessionEvent do
    key :required, [:etype, :session_id]
    property :id, type: :integer, format: :int32, description: RootApiDoc::ID_DESC
    property :session_id, type: :integer, format: :int32, description: SESSION_ID_DESC
    property :etype, type: :string, description: ETYPE_DESC, enum: ETYPE_ENUM
    property :command, type: :string, description: COMMAND_DESC
    property :output, type: :string, description: OUTPUT_DESC
    property :local_path, type: :string, description: LOCAL_PATH_DESC, example: LOCAL_PATH_EXAMPLE
    property :remote_path, type: :string, description: REMOTE_PATH_DESC, example: REMOTE_PATH_EXAMPLE
    property :created_at, type: :string, format: :date_time, description: RootApiDoc::CREATED_AT_DESC
  end

  swagger_path '/api/v1/session-events' do
    # Swagger documentation for /api/v1/session-events GET
    operation :get do
      key :description, 'Return session events that are stored in the database.'
      key :tags, [ 'session_event' ]

      response 200 do
        key :description, 'Returns session event data.'
        schema do
          key :type, :array
          items do
            key :'$ref', :SessionEvent
          end
        end
      end
    end

    # Swagger documentation for /api/v1/session events POST
    operation :post do
      key :description, 'Create a session events entry.'
      key :tags, [ 'session_event' ]

      parameter do
        key :in, :body
        key :name, :body
        key :description, 'The attributes to assign to the session event.'
        key :required, true
        schema do
          property :etype, type: :string, required: true, description: ETYPE_DESC, enum: ETYPE_ENUM
          property :session, '$ref' => :Session, required: true
          property :command, type: :string, description: COMMAND_DESC
          property :output, type: :string, description: OUTPUT_DESC
          property :local_path, type: :string, description: LOCAL_PATH_DESC, example: LOCAL_PATH_EXAMPLE
          property :remote_path, type: :string, description: REMOTE_PATH_DESC, example: REMOTE_PATH_EXAMPLE
        end
      end

      response 200 do
        key :description, 'Successful operation.'
        schema do
          key :type, :object
          key :'$ref', :SessionEvent
        end
      end
    end
  end
end
