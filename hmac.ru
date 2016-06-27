require 'openssl'

def signature(message, key)
  signature = 'sha1=' + OpenSSL::HMAC.hexdigest(OpenSSL::Digest.new('sha1'), key, message)
end

def verify_signature(message, key, expected)
  signature = signature(message, key)
  return "Signatures didn't match!" unless signature == expected
  'Signature does match'
end

data = [
    ['', ''],
    ['x', 'x'],
    ['a', 'b'],
    ['c', 'd'],
]

data.each { |key, message|
  puts "Signature for key '#{key}', message '#{message}' is #{signature(message, key)}"
}