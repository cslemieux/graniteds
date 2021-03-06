/**
 * Generated by Gas3 v2.2.0 (Granite Data Services).
 *
 * WARNING: DO NOT CHANGE THIS FILE. IT MAY BE OVERWRITTEN EACH TIME YOU USE
 * THE GENERATOR. INSTEAD, EDIT THE INHERITED CLASS (Person.as).
 */

package org.granite.example.addressbook.entity {

    import flash.utils.IDataInput;
    import flash.utils.IDataOutput;
    import mx.collections.ListCollectionView;
    import org.granite.example.addressbook.entity.embed.Document;
    import org.granite.example.addressbook.entity.types.DocumentedEntity;
    import org.granite.example.addressbook.entity.types.NamedEntity;
    import org.granite.meta;
    import org.granite.util.Enum;

    use namespace meta;

    [Bindable]
    public class PersonBase extends AbstractEntity implements NamedEntity, DocumentedEntity {

        private var _contacts:ListCollectionView;
        private var _document:Document;
        private var _firstName:String;
        private var _fullName:String;
        private var _lastName:String;
        private var _mainContact:Contact;
        private var _salutation:Person$Salutation;

        public function set contacts(value:ListCollectionView):void {
            _contacts = value;
        }
        public function get contacts():ListCollectionView {
            return _contacts;
        }

        public function set document(value:Document):void {
            _document = value;
        }
        public function get document():Document {
            return _document;
        }

        public function set firstName(value:String):void {
            _firstName = value;
        }
        public function get firstName():String {
            return _firstName;
        }

        [Bindable(event="unused")]
        public function get fullName():String {
            return _fullName;
        }

        public function set lastName(value:String):void {
            _lastName = value;
        }
        public function get lastName():String {
            return _lastName;
        }

        public function set mainContact(value:Contact):void {
            _mainContact = value;
        }
        public function get mainContact():Contact {
            return _mainContact;
        }

        public function set salutation(value:Person$Salutation):void {
            _salutation = value;
        }
        public function get salutation():Person$Salutation {
            return _salutation;
        }

        public override function readExternal(input:IDataInput):void {
            super.readExternal(input);
            if (meta::isInitialized()) {
                _contacts = input.readObject() as ListCollectionView;
                _document = input.readObject() as Document;
                _firstName = input.readObject() as String;
                _fullName = input.readObject() as String;
                _lastName = input.readObject() as String;
                _mainContact = input.readObject() as Contact;
                _salutation = Enum.readEnum(input) as Person$Salutation;
            }
        }

        public override function writeExternal(output:IDataOutput):void {
            super.writeExternal(output);
            if (meta::isInitialized()) {
                output.writeObject(_contacts);
                output.writeObject(_document);
                output.writeObject(_firstName);
                output.writeObject(_fullName);
                output.writeObject(_lastName);
                output.writeObject(_mainContact);
                output.writeObject(_salutation);
            }
        }
    }
}