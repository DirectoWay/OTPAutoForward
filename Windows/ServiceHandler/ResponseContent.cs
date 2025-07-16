namespace OTPAutoForward.ServiceHandler
{
    public class ResponseContent
    {
        public ResponseContent()
        {
        }

        public ResponseContent(bool status)
        {
            Status = status;
        }

        public bool Status { get; set; }
        public string Code { get; set; }

        public string Message { get; set; }

        public object Data { get; set; }

        public ResponseContent OK()
        {
            Status = true;
            return this;
        }

        public static ResponseContent Instance => new ResponseContent();

        public ResponseContent OK(string message, object data = null)
        {
            Status = true;
            Message = message;
            Data = data;
            return this;
        }

        public ResponseContent Error(string message = null)
        {
            Status = false;
            Message = message;
            return this;
        }
    }
}